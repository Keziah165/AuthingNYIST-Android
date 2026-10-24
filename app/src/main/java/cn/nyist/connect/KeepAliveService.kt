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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台保活服务：每 30 秒检查一次在线状态，掉线自动重连。
 *
 * 移植自 portal.go 的 Run 循环并按移动端场景调整：
 * - 认证被拒 / 其他账号在线 → 立即停止（与上游一致，避免锁号）
 * - 暂时性失败最多连续 5 次，按 2/4/8/16 秒退避（与上游一致）
 * - 上游差异：完全没有网络时不计失败、等待网络恢复（桌面版保活假设常开网络）
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keepalive"
        private const val NOTIFICATION_ID = 1

        /** UI 观察用：保活循环的最新一条状态文本；服务停止后为 null。 */
        val statusText = MutableStateFlow<String?>(null)

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
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { hasNetwork = true }
        override fun onLost(network: Network) { hasNetwork = false }
    }

    @Volatile
    private var hasNetwork: Boolean = false

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        cm = getSystemService(ConnectivityManager::class.java)
        createChannel()
        updateNotification("正在启动…")
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("正在启动…"),
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        try {
            cm?.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed", e)
            hasNetwork = true
        }
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
        scope.launch {
            keepAliveLoop()
        }
    }

    private suspend fun keepAliveLoop() {
        var failures = 0
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val cfg = ConfigStore.load(this)
                if (!cfg.hasCredentials()) {
                    statusText.value = "未配置账号，保活停止"
                    break
                }
                if (!hasNetwork) {
                    statusText.value = "等待网络连接…"
                    // 无网络不计失败，最多等 60 秒再复查
                    var waited = 0
                    while (!hasNetwork && waited < 60_000 &&
                        kotlinx.coroutines.currentCoroutineContext().isActive
                    ) {
                        delay(1_000); waited += 1_000
                    }
                    continue
                }
                try {
                    val s = Srun.connect(cfg)
                    failures = 0
                    statusText.value = "已在线 · ${s.username}" + (if (s.ip.isNotEmpty()) " · ${s.ip}" else "")
                } catch (e: Srun.AuthException) {
                    statusText.value = e.message ?: "发生错误"
                    if (e.kind != Srun.ErrorKind.NETWORK) break // 拒绝认证/他人在线：终止
                    failures++
                    if (failures >= 5) {
                        statusText.value = "连续失败 ${failures} 次，保活停止：${e.message}"
                        break
                    }
                }
                val delayMs = if (failures > 0) (1L shl failures) * 1000 else 30_000
                delay(delayMs)
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
