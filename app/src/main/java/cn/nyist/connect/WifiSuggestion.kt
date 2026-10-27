package cn.nyist.connect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 把校园网以系统「建议网络」(WifiNetworkSuggestion) 的形式交给 Android 托管。
 *
 * 效果：首次由用户在系统弹窗里允许一次，之后只要进入校园网覆盖范围，
 * 系统就会自动连上该 SSID——凌晨断连、离开学校再回来都不需要手动选网络。
 *
 * NYIST 的 nyist-tec / nyist-stu 是**开放网络 + Web 门户**：
 * Wi-Fi 层不加密也不需要 802.1X 凭据，连上后由 srun 门户完成身份认证。
 * 因此这里只提交「开放网络」建议，不带任何安全配置；门户认证由 Srun 负责。
 * 已保存过的同名网络优先级高于建议网络，不会被这里覆盖或破坏。
 */
object WifiSuggestion {

    private const val TAG = "WifiSuggestion"

    /**
     * 提交建议网络。返回 null 表示成功；否则返回可以直接展示给用户的原因。
     * 注意：会先清空本应用此前提交的全部建议（空列表 = 全部移除），
     * 这样改了密码后能及时生效。
     */
    fun apply(ctx: Context, cfg: Srun.Config): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "Android 10 以下系统不支持自动连接校园 Wi-Fi，请手动连接"
        }
        if (!cfg.hasCredentials()) return "请先填写账号和密码"
        if (!hasPermission(ctx)) return "缺少「附近的 Wi-Fi」权限，无法自动连接校园 Wi-Fi"
        val wm = ctx.getSystemService(WifiManager::class.java) ?: return "无法获取 Wi-Fi 服务"
        return try {
            runCatching { wm.removeNetworkSuggestions(emptyList<WifiNetworkSuggestion>()) }
            val status = wm.addNetworkSuggestions(listOf(build(cfg)))
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) null
            else "添加校园网到系统建议失败（状态码 $status）"
        } catch (e: Exception) {
            Log.w(TAG, "apply suggestion failed", e)
            "自动连接校园 Wi-Fi 失败：${e.message}"
        }
    }

    /** Android 13 起用 NEARBY_WIFI_DEVICES，之前用定位权限（系统强制要求）。 */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    private fun build(cfg: Srun.Config): WifiNetworkSuggestion {
        // NYIST 校园无线是开放网络，不设任何安全参数；
        // 身份认证由 srun 门户（Srun.connect）在连上 Wi-Fi 后完成。
        return WifiNetworkSuggestion.Builder()
            .setSsid(cfg.network)
            .setIsHiddenSsid(false)
            .build()
    }
}
