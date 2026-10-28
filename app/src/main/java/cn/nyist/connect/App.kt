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
import kotlin.concurrent.thread

/**
 * 全局 Application：注册 Wi-Fi 网络回调，作为「应用进程存活时」的即时触发点。
 * 常驻的 KeepAliveService 自己也会监听 Wi-Fi，这里主要保证：
 * 应用被打开/进程在跑时，一关联到 Wi-Fi 立刻拉起保活服务去认证。
 */
class App : Application() {

    companion object {
        private const val TAG = "NyistApp"
        private const val WIFI_JOB_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        // 启动时把校园网交给系统托管：之后进入覆盖范围由系统自动入网
        val cfg = ConfigStore.load(this)
        if (cfg.autoConnect && cfg.hasCredentials()) {
            thread(name = "wifi-suggestion") { WifiSuggestion.apply(this, cfg) }
        }
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            // 只按「Wi-Fi 已关联」判断，不能要求 NET_CAPABILITY_INTERNET：
            // 校园网在认证通过前并不具备互联网能力，加了该条件回调永远不触发。
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
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
        if (KeepAliveService.running) return // 保活已在运行，服务内的回调会立即认证
        if (!KeepAliveService.start(this)) {
            Log.i(TAG, "FGS start blocked in background, scheduling job")
            scheduleWifiJob(this)
        }
    }

    private fun scheduleWifiJob(context: Context) {
        val js = getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(WIFI_JOB_ID) != null) return
        // 用 NETWORK_TYPE_ANY：校园网在认证前既不是 UNMETERED，也没有 INTERNET 能力，
        // 用 UNMETERED 会导致任务永远无法执行。
        val builder = JobInfo.Builder(
            WIFI_JOB_ID, ComponentName(context, WifiJobService::class.java)
        ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
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
