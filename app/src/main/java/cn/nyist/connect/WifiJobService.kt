package cn.nyist.connect

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import kotlin.concurrent.thread

/**
 * 后台 Wi-Fi 触发的一次性认证任务。
 * Android 12+ 不允许后台直接启动前台服务，这里通过加急 Job 完成认证。
 */
class WifiJobService : JobService() {

    companion object {
        private const val TAG = "WifiJobService"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val cfg = ConfigStore.load(this)
        if (!cfg.autoConnect || !cfg.hasCredentials()) {
            jobFinished(params, false)
            return false
        }
        thread {
            try {
                val cm = getSystemService(android.net.ConnectivityManager::class.java)
                val net = CampusNetwork.findWifi(cm)
                val s = Srun.connect(cfg, net)
                Log.i(TAG, "auto connect ok: ${s.username}")
                if (net != null) CampusNetwork.markOnline(cm, net)
                if (cfg.keepAlive) {
                    KeepAliveService.start(this)
                }
            } catch (e: Srun.AuthException) {
                // 认证被拒或他人在线：不重试，避免锁号
                Log.w(TAG, "auto connect rejected: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "auto connect failed", e)
            }
            jobFinished(params, false)
        }
        return true // 工作在后台线程进行
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
