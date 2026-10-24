package cn.nyist.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：仅在用户勾选「开机自启」+「自动连接」且已保存账号时启动保活。
 * BOOT_COMPLETED 属于前台服务启动豁免场景。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = ConfigStore.load(context)
        if (cfg.autoStart && cfg.autoConnect && cfg.hasCredentials()) {
            KeepAliveService.start(context)
        }
    }
}
