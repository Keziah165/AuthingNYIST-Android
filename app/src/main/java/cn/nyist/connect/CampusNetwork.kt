package cn.nyist.connect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log

/**
 * 校园 Wi-Fi 的 Network 定位与状态上报。
 *
 * 关键点：手机同时开着移动数据时，系统默认网络是移动数据，而校园网在认证前
 * 没有互联网能力。认证请求必须显式绑定到 Wi-Fi 的 Network，否则会走移动数据，
 * 表现为「认证一直失败」或「必须先打开认证网页才能上网」。
 */
object CampusNetwork {

    private const val TAG = "CampusNetwork"

    /** 找到当前已关联的 Wi-Fi 网络（认证前没有 INTERNET 能力，只能用传输类型判断）。 */
    fun findWifi(cm: ConnectivityManager?): Network? {
        if (cm == null) return null
        return try {
            cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (e: Exception) {
            Log.w(TAG, "findWifi failed", e)
            null
        }
    }

    /** 读取当前连接的 SSID；无权限或未知时返回 null（调用方按「未知」处理）。 */
    fun currentSsid(context: Context): String? {
        return try {
            val wm = context.getSystemService(WifiManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            val raw = wm.connectionInfo?.ssid ?: return null
            if (raw.isEmpty() || raw == WifiManager.UNKNOWN_SSID) return null
            raw.removeSurrounding("\"")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 告诉系统「这个 Wi-Fi 现在已经能上网了」。
     * 认证成功后调用，系统会撤掉「登录到网络」提示，并把它当作可用网络使用，
     * 这样蜂窝数据也不会再抢占默认网络。
     */
    fun markOnline(cm: ConnectivityManager?, network: Network) {
        if (cm == null) return
        try {
            cm.reportNetworkConnectivity(network, true)
        } catch (e: Exception) {
            Log.i(TAG, "reportNetworkConnectivity failed: ${e.message}")
        }
    }

    /** 判断网络是否为 Wi-Fi（回调里用）。 */
    fun isWifi(cm: ConnectivityManager?, network: Network): Boolean = try {
        cm?.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) {
        false
    }
}
