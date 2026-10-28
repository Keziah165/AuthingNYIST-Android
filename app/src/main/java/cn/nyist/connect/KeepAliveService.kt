package cn.nyist.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 前台保活服务：常驻后台，负责「一开 Wi-Fi 就自动连上并认证」和「全程保持在线」。
 *
 * - 注册 Wi-Fi 网络回调，一旦关联到校园 Wi-Fi 立即唤醒循环认证（不等 30 秒轮询）；
 * - 所有认证请求绑定到该 Wi-Fi 的 Network，避免同时开着移动数据时请求走错网络；
 * - 认证成功后调用 reportNetworkConnectivity，让系统认定该网络可用，
 *   从而撤掉「登录到网络」提示，不需要用户再去点认证网页；
 * - 之后每 30 秒复查一次在线状态，掉线（凌晨断连等）自动重连认证。
 *
 * 移植自 portal.go 的 Run 循环并按移动端场景调整：
 * - 认证被拒 / 其他账号在线 → 立即停止（与上游一致，避免锁号）
 * - 暂时性网络失败按 2/4/8…60 秒退避重试，不停止（Wi-Fi 漫游、portal 抖动很常见）
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1

        /** UI 观察用：保活循环的最新一条状态文本；服务停止后为 null。 */
        val statusText = MutableStateFlow<String?>(null)

        /** 服务是否正在运行（比观察 statusText 可靠，服务刚启动时状态可能还没写入）。 */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context): Boolean {
            return try {
                val i = Intent(context, KeepAliveService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(context, i)
                true
            } catch (e: Exception) {
                // Android 12+ 后台启动前台服务受限时返回 false，由调用方降级处理
                Log.w(TAG, "cannot start foreground service", e)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var nm: NotificationManager
    private var cm: ConnectivityManager? = null

    /** 当前校园 Wi-Fi 的 Network：认证请求必须绑定它。 */
    @Volatile
    private var wifi: Network? = null

    /** Wi-Fi 上下线时唤醒循环，实现「打开 Wi-Fi 立即认证」。 */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (CampusNetwork.isWifi(cm, network)) {
                wifi = network
                wake.trySend(Unit)
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (CampusNetwork.isWifi(cm, network)) {
                wifi = network
                wake.trySend(Unit)
            }
        }

        override fun onLost(network: Network) {
            if (wifi == network) {
                wifi = null
                wake.trySend(Unit)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        nm = getSystemService(NotificationManager::class.java)
            ?: throw IllegalStateException("NotificationManager 不可用")
        cm = getSystemService(ConnectivityManager::class.java)
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("正在启动…"),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        try {
            // 同 App：不能要求 NET_CAPABILITY_INTERNET，校园网认证前不具备该能力
            cm?.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                networkCallback
            )
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed", e)
        }
        wifi = CampusNetwork.findWifi(cm)
        // 状态变化同步到常驻通知
        scope.launch {
            KeepAliveService.statusText.collect { text ->
                if (text != null) updateNotification(text)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (statusText.value == null) statusText.value = "保活运行中"
        runLoopOnceIfIdle()
        return START_STICKY
    }

    private var loopLaunched = false
    private fun runLoopOnceIfIdle() {
        if (loopLaunched) return
        loopLaunched = true
        scope.launch { keepAliveLoop() }
    }

    private suspend fun keepAliveLoop() {
        var failures = 0
        try {
            while (currentCoroutineContext().isActive) {
                val cfg = ConfigStore.load(this)
                if (!cfg.hasCredentials()) {
                    statusText.value = "未配置账号，保活停止"
                    break
                }

                val net = wifi ?: CampusNetwork.findWifi(cm)?.also { wifi = it }
                if (net == null) {
                    statusText.value = "等待连接校园 Wi-Fi…"
                    withTimeoutOrNull(30_000) { wake.receive() }
                    continue
                }

                // 能读到 SSID 时先确认是不是校园网，避免在家庭/公共 Wi-Fi 上反复试认证
                val ssid = CampusNetwork.currentSsid(this)
                if (ssid != null && ssid != cfg.network) {
                    statusText.value = "当前 Wi-Fi 为 $ssid，等待连接 ${cfg.network}"
                    withTimeoutOrNull(30_000) { wake.receive() }
                    continue
                }

                try {
                    val s = Srun.connect(cfg, net)
                    failures = 0
                    // 告诉系统这个 Wi-Fi 已经能上网：撤掉「登录到网络」提示，避免抢占默认网络
                    CampusNetwork.markOnline(cm, net)
                    statusText.value = "已在线 · ${s.username}" + (if (s.ip.isNotEmpty()) " · ${s.ip}" else "")
                } catch (e: Srun.AuthException) {
                    statusText.value = e.message ?: "发生错误"
                    if (e.kind != Srun.ErrorKind.NETWORK) break // 拒绝认证/他人在线：终止，避免锁号
                    failures++ // 网络类失败只退避重试，不停止（漫游/portal 抖动很常见）
                }

                val delayMs = if (failures > 0) (1L shl failures.coerceAtMost(6)) * 1000 else 30_000
                withTimeoutOrNull(delayMs) { wake.receive() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "keepalive loop crashed", e)
        } finally {
            statusText.value = null
            loopLaunched = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        try {
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        statusText.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun updateNotification(text: String) {
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.notif_title_running))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
