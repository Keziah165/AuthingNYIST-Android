package cn.nyist.connect

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

/**
 * 全局 Application：注册 Wi-Fi 网络回调。
 * 连上 Wi-Fi 且开启了「自动连接」时触发一次认证：
 * - 应用在前台 → 直接启动保活服务；
 * - 应用在后台（Android 12+ 限制后台启动前台服务）→ 调度一次加急 Job 执行认证。
 */
class App : Application() {

    companion object {
        private const val TAG = "NyistApp"
        private const val WIFI_JOB_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        onWifiAvailable()
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback failed", e)
        }
    }

    private fun onWifiAvailable() {
        val cfg = ConfigStore.load(this)
        if (!cfg.autoConnect || !cfg.hasCredentials()) return
        if (KeepAliveService.statusText.value != null) return // 保活已在运行
        if (!KeepAliveService.start(this)) {
            Log.i(TAG, "FGS start blocked in background, scheduling job")
            scheduleWifiJob(this)
        }
    }

    private fun scheduleWifiJob(context: Context) {
        val js = getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(WIFI_JOB_ID) != null) return
        val builder = JobInfo.Builder(
            WIFI_JOB_ID, ComponentName(context, WifiJobService::class.java)
        ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            builder.setExpedited(true)
        }
        try {
            js.schedule(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "schedule job failed", e)
        }
    }
}
