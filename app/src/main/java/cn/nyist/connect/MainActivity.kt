package cn.nyist.connect

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cn.nyist.connect.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var busy = false
    private var autoConnectDone = false

    private val networks = listOf(Srun.NETWORK_TEC, Srun.NETWORK_STU)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.networkSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, networks
        )

        val cfg = ConfigStore.load(this)
        b.networkSpinner.setSelection(networks.indexOf(cfg.network).coerceAtLeast(0))
        b.etUsername.setText(cfg.username)
        b.etPassword.setText(cfg.password)
        // 旧配置可能只开了部分自动化，这里合并为一个「自动保持在线」开关
        b.swAutoKeepOnline.isChecked = cfg.autoConnect || cfg.keepAlive || cfg.autoStart

        b.btnConnect.setOnClickListener { onConnect() }
        b.btnDisconnect.setOnClickListener { onDisconnect() }
        b.btnCheck.setOnClickListener { onCheck() }

        // 打开「自动保持在线」时立即把校园网交给系统托管（改密码后重新提交）
        b.swAutoKeepOnline.setOnCheckedChangeListener { _, checked ->
            if (!checked) return@setOnCheckedChangeListener
            requestAutoPermissions()
            val c = runCatching { configFromUi() }.getOrNull() ?: return@setOnCheckedChangeListener
            if (c.hasCredentials()) {
                Thread { WifiSuggestion.apply(this, c) }.start()
            }
        }

        // 保活服务状态回显
        lifecycleScope.launch {
            KeepAliveService.statusText.collect { text ->
                if (text != null) {
                    b.tvService.text = getString(R.string.notif_title_running) + " · " + text
                    b.tvService.visibility = View.VISIBLE
                } else {
                    b.tvService.visibility = View.GONE
                }
            }
        }

        // 打开应用时若开启自动连接，自动认证一次（不在校园网范围则静默跳过）
        if (cfg.autoConnect && cfg.hasCredentials() && !autoConnectDone) {
            autoConnectDone = true
            Thread { WifiSuggestion.apply(this, cfg) }.start()
            if (hasWifiNetwork()) {
                doWork {
                    runCatching { Srun.connect(ConfigStore.load(this@MainActivity)) }
                        .onSuccess { KeepAliveService.start(this@MainActivity) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // UI → 配置
    // ------------------------------------------------------------------

    private fun configFromUi(): Srun.Config {
        val pos = b.networkSpinner.selectedItemPosition
        val auto = b.swAutoKeepOnline.isChecked
        val cfg = Srun.Config(
            network = networks[if (pos in networks.indices) pos else 0],
            username = b.etUsername.text?.toString()?.trim().orEmpty(),
            password = b.etPassword.text?.toString().orEmpty(),
            autoConnect = auto,
            autoStart = auto,
            keepAlive = auto,
        )
        ConfigStore.save(this, cfg) // 保存前会校验，非法配置抛 IllegalArgumentException
        return cfg
    }

    private fun saveUiQuietly() {
        runCatching { configFromUi() }
    }

    // ------------------------------------------------------------------
    // 按钮
    // ------------------------------------------------------------------

    private fun onConnect() {
        val cfg = try {
            configFromUi()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "配置不合法", Toast.LENGTH_LONG).show()
            return
        }
        if (!cfg.hasCredentials()) {
            Toast.makeText(this, "请先填写账号和密码", Toast.LENGTH_LONG).show()
            return
        }
        if (cfg.autoConnect) requestAutoPermissions()
        doWork {
            val cur = ConfigStore.load(this@MainActivity)
            // 开启自动保持在线时，先把校园网交给系统托管，断连/回到学校由系统自动入网
            if (cur.autoConnect && WifiSuggestion.hasPermission(this@MainActivity)) {
                WifiSuggestion.apply(this@MainActivity, cur)?.let {
                    return@doWork Result.failure<Nothing>(IllegalStateException(it))
                }
            }
            if (!hasWifiNetwork() && !waitForWifi(10_000)) {
                withContext(Dispatchers.Main) { openWifiPanel() }
                return@doWork Result.failure<Nothing>(
                    IllegalStateException("已打开 Wi-Fi 设置，请连接校园网 nyist-tec 后会自动认证")
                )
            }
            val s = Srun.connect(cur)
            if (cur.autoConnect) KeepAliveService.start(this@MainActivity)
            Result.success(s)
        }
    }

    /** 是否存在已关联的 Wi-Fi（认证前没有互联网，不能用 INTERNET 能力判断）。 */
    private fun hasWifiNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** 等系统把建议网络连上（自动入网通常需要几秒）。 */
    private suspend fun waitForWifi(timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (hasWifiNetwork()) return true
            kotlinx.coroutines.delay(500)
            waited += 500
        }
        return hasWifiNetwork()
    }

    private fun openWifiPanel() {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI)
        } else {
            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        }
        runCatching { startActivity(intent) }
    }

    private fun onDisconnect() {
        val cfg = try {
            configFromUi()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "配置不合法", Toast.LENGTH_LONG).show()
            return
        }
        if (cfg.username.isEmpty()) {
            Toast.makeText(this, "请先填写账号", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_logout_title)
            .setMessage(R.string.dialog_logout_msg)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                doWork(successFallback = "已断开认证") {
                    runCatching { Srun.disconnect(ConfigStore.load(this@MainActivity)) }
                        .onSuccess { KeepAliveService.stop(this@MainActivity) }
                }
            }
            .show()
    }

    private fun onCheck() {
        saveUiQuietly()
        doWork {
            runCatching {
                Srun.inspectStatus(ConfigStore.load(this@MainActivity), hasActiveNetwork())
            }
        }
    }

    /** 是否存在活动网络（含未通过认证的校园网），用于区分「没连网」和「portal 不可达」。 */
    private fun hasActiveNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ------------------------------------------------------------------
    // 通用执行：后台跑，结果回填状态卡片
    // ------------------------------------------------------------------

    private fun doWork(successFallback: String? = null, block: suspend () -> Result<*>) {
        if (busy) return
        busy = true
        b.progress.visibility = View.VISIBLE
        b.btnConnect.isEnabled = false
        b.btnDisconnect.isEnabled = false
        b.btnCheck.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            showStatus(result, successFallback)
            b.progress.visibility = View.GONE
            b.btnConnect.isEnabled = true
            b.btnDisconnect.isEnabled = true
            b.btnCheck.isEnabled = true
            busy = false
        }
    }

    private fun showStatus(result: Result<*>, successFallback: String?) {
        val s = result.getOrNull() as? Srun.Status
        when {
            s != null -> {
                b.tvStatus.text = s.message
                b.tvStatusUser.text = if (s.username.isNotEmpty()) "在线账号：${s.username}" else ""
                b.tvStatusIp.text = if (s.ip.isNotEmpty()) "IP：${s.ip}" else ""
            }
            result.isSuccess -> {
                b.tvStatus.text = successFallback ?: "操作成功"
                b.tvStatusUser.text = ""
                b.tvStatusIp.text = ""
            }
            else -> {
                b.tvStatus.text = result.exceptionOrNull()?.message ?: "发生错误"
                b.tvStatusUser.text = ""
                b.tvStatusIp.text = ""
                Toast.makeText(this, result.exceptionOrNull()?.message ?: "发生错误", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestAutoPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (!WifiSuggestion.hasPermission(this)) {
            wanted += WifiSuggestion.requiredPermission()
        }
        if (wanted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 100)
        }
    }
}
