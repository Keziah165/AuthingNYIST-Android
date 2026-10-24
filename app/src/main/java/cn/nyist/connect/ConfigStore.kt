package cn.nyist.connect

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 配置存储。密码使用 EncryptedSharedPreferences（Android Keystore 加密），
 * 对应桌面版的 DPAPI / 0600 文件保护；Keystore 异常时退回普通 prefs。
 */
object ConfigStore {

    private const val TAG = "ConfigStore"
    private const val FILE_SECURE = "authing_nyist_secure"
    private const val FILE_PLAIN = "authing_nyist"

    private const val K_NETWORK = "network"
    private const val K_USERNAME = "username"
    private const val K_PASSWORD = "password"
    private const val K_AUTO_CONNECT = "autoConnect"
    private const val K_AUTO_START = "autoStart"
    private const val K_KEEP_ALIVE = "keepAlive"
    private const val K_HOST = "host"
    private const val K_AC_ID = "acId"

    fun load(ctx: Context): Srun.Config {
        val p = prefs(ctx)
        val cfg = Srun.Config(
            network = p.getString(K_NETWORK, Srun.NETWORK_TEC) ?: Srun.NETWORK_TEC,
            username = p.getString(K_USERNAME, "").orEmpty(),
            password = p.getString(K_PASSWORD, "").orEmpty(),
            autoConnect = p.getBoolean(K_AUTO_CONNECT, false),
            autoStart = p.getBoolean(K_AUTO_START, false),
            keepAlive = p.getBoolean(K_KEEP_ALIVE, false),
            host = p.getString(K_HOST, Srun.DEFAULT_HOST) ?: Srun.DEFAULT_HOST,
            acId = p.getString(K_AC_ID, Srun.DEFAULT_AC_ID) ?: Srun.DEFAULT_AC_ID,
        )
        return try {
            Srun.validate(cfg)
            cfg
        } catch (e: Exception) {
            // 配置损坏时回到默认值，不阻塞启动
            Srun.Config()
        }
    }

    fun save(ctx: Context, cfg: Srun.Config) {
        Srun.validate(cfg)
        prefs(ctx).edit()
            .putString(K_NETWORK, cfg.network)
            .putString(K_USERNAME, cfg.username)
            .putString(K_PASSWORD, cfg.password)
            .putBoolean(K_AUTO_CONNECT, cfg.autoConnect)
            .putBoolean(K_AUTO_START, cfg.autoStart)
            .putBoolean(K_KEEP_ALIVE, cfg.keepAlive)
            .putString(K_HOST, cfg.host)
            .putString(K_AC_ID, cfg.acId)
            .apply()
    }

    private fun prefs(ctx: Context): SharedPreferences {
        try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx, FILE_SECURE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
            return ctx.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
        }
    }
}
