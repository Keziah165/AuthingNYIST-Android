package cn.nyist.connect

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NYIST 校园网 srun（深澜）认证协议的 Kotlin 实现。
 *
 * 移植自 GUYU2233/AuthingNYIST（internal/app/portal.go 与 libauth 包），
 * 上游协议实现源自 Palvef/AuthingNYIST，灵感来自 z4yx/GoAuthing。
 * 本项目遵循 GPL-3.0 许可证。
 */
object Srun {

    const val DEFAULT_HOST = "https://auth.nyist.edu.cn"
    const val DEFAULT_AC_ID = "1"
    const val NETWORK_TEC = "nyist-tec"
    const val NETWORK_STU = "nyist-stu"

    private const val CALLBACK = "authing_callback"
    private const val USER_AGENT = "AuthingNYIST-Android"
    private const val TIMEOUT_MS = 12_000
    private const val DELTA = -1640531527 // 0x9E3779B9 的 32 位有符号表示

    /** 错误类别：NETWORK 为暂时性失败；CREDENTIALS / OTHER_ACCOUNT 为终止性失败。 */
    enum class ErrorKind { NETWORK, CREDENTIALS, OTHER_ACCOUNT }

    class AuthException(message: String, val kind: ErrorKind = ErrorKind.NETWORK) : Exception(message)

    data class Config(
        val network: String = NETWORK_TEC,
        val username: String = "",
        val password: String = "",
        val autoConnect: Boolean = false,
        val autoStart: Boolean = false,
        val keepAlive: Boolean = false,
        val host: String = DEFAULT_HOST,
        val acId: String = DEFAULT_AC_ID,
    ) {
        fun hasCredentials(): Boolean = username.isNotEmpty() && password.isNotEmpty()
    }

    data class Status(
        val online: Boolean,
        val username: String,
        val ip: String,
        val message: String,
        val domain: String = "",
    )

    // ------------------------------------------------------------------
    // 配置校验（对应 config.go Validate）
    // ------------------------------------------------------------------

    fun hostUrl(h: String): String {
        val s = if (!h.contains("://")) "https://$h" else h
        return s.trimEnd('/')
    }

    fun validate(cfg: Config) {
        require(cfg.network == NETWORK_TEC || cfg.network == NETWORK_STU) { "网络必须是 $NETWORK_TEC 或 $NETWORK_STU" }
        if (cfg.username.isNotEmpty()) {
            require(cfg.username.trim() == cfg.username && cfg.username.none { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) {
                "账号不能包含空白字符"
            }
            require(cfg.username.count { it == '@' } <= 1 && !cfg.username.startsWith("@") && !cfg.username.endsWith("@")) {
                "账号域名格式不正确"
            }
        }
        require(cfg.password.none { it == '\r' || it == '\n' || it == '\u0000' }) { "密码包含非法字符" }
        val u = runCatching { URI(hostUrl(cfg.host)) }.getOrNull()
        require(u != null && u.host != null && (u.scheme == "http" || u.scheme == "https") &&
            u.userInfo == null && (u.path == null || u.path == "/" || u.path.isEmpty()) &&
            u.query == null && u.fragment == null
        ) { "服务器地址必须是合法的 HTTP(S) 来源，不含路径、参数或片段" }
        require((cfg.acId.toIntOrNull() ?: 0) > 0) { "acId 必须是正整数" }
    }

    // ------------------------------------------------------------------
    // 对外操作（对应 portal.go 的 Check / Connect / Disconnect）
    // ------------------------------------------------------------------

    /** 检查在线状态。 */
    fun check(cfg: Config): Status {
        validate(cfg)
        val r = portalGet(cfg.host, "/cgi-bin/rad_user_info", emptyMap())
        val err = r.optString("error")
        val user = r.optString("user_name")
        var ip = r.optString("online_ip")
        if (ip.isEmpty()) ip = r.optString("online_ip6")
        return when {
            err == "ok" && user.isNotEmpty() -> {
                val same = sameAccount(cfg.username, user, r.optString("domain"))
                Status(same, user, ip, if (same) "已连接" else "其他账号在线", r.optString("domain"))
            }
            err == "not_online_error" || err == "not_online" || err == "offline" ->
                Status(false, "", ip, "校园网未认证")
            else -> throw AuthException("认证服务器未返回有效的在线状态")
        }
    }

    /**
     * 状态诊断（对应上游 v1.1.1 的 InspectStatus，只读、不做任何连接/注销）：
     * 区分「本机未连接网络」「本机已联网但认证服务器不可达」与「校园网未认证」。
     * Android 不读取 SSID（需要定位权限），Wi-Fi 一致性由用户自行确认。
     *
     * @param hasNetwork 调用方通过 ConnectivityManager 判断是否存在活动网络
     */
    fun inspectStatus(cfg: Config, hasNetwork: Boolean): Status {
        validate(cfg)
        if (!hasNetwork) {
            return Status(false, "", "", "未连接网络，请先连接校园 Wi-Fi 或有线网络")
        }
        return try {
            check(cfg)
        } catch (e: Srun.AuthException) {
            if (e.kind == ErrorKind.CREDENTIALS || e.kind == ErrorKind.OTHER_ACCOUNT) throw e
            throw AuthException("本机已联网，但校园认证状态不可达：${e.message}", ErrorKind.NETWORK)
        }
    }

    /**
     * 认证（登录）。若发现其他账号在线则拒绝，避免误断他人会话。
     * Android 上不切换 Wi-Fi，请先在系统设置中连上校园 Wi-Fi。
     */
    fun connect(cfg: Config): Status {
        validate(cfg)
        require(cfg.hasCredentials()) { "请先填写账号和密码" }
        val s = check(cfg)
        if (!s.online && s.username.isNotEmpty()) {
            throw AuthException("检测到其他账号在线（${s.username}），已停止以避免断开他人会话", ErrorKind.OTHER_ACCOUNT)
        }
        if (s.online) return s
        authenticate(cfg)
        val after = check(cfg)
        if (!after.online) throw AuthException("认证请求已发送但账号未上线", ErrorKind.NETWORK)
        return after
    }

    /**
     * 注销（登出）。当前在线的是其他账号时拒绝。
     * 对齐上游 v1.1.1 / 学校官方页面：使用在线会话的完整账号和 IP 发送注销，
     * 不获取 challenge、不携带 password/info/chksum；服务器确认离线后才算成功。
     */
    fun disconnect(cfg: Config) {
        validate(cfg)
        require(cfg.username.isNotEmpty()) { "请先填写账号" }
        val s = check(cfg)
        if (s.username.isEmpty()) return // 无人在线，无需注销
        if (!s.online) {
            throw AuthException("当前在线的是其他账号（${s.username}），拒绝注销", ErrorKind.OTHER_ACCOUNT)
        }
        var username = s.username
        if (!username.contains('@') && s.domain.isNotEmpty()) {
            username += "@" + s.domain.removePrefix("@")
        }
        val r = portalGet(
            cfg.host, "/cgi-bin/srun_portal",
            mapOf("action" to "logout", "username" to username, "ip" to s.ip, "ac_id" to cfg.acId)
        )
        if (r.optString("error") != "ok") {
            throw AuthException("认证服务器拒绝了注销请求，会话可能仍在线")
        }
        // 不轻信注销成功：轮询确认本账号确实已离线
        for (attempt in 0 until 4) {
            val st = check(cfg)
            if (st.username.isEmpty()) return
            if (!st.online) {
                throw AuthException("当前在线的是其他账号（${st.username}），拒绝注销", ErrorKind.OTHER_ACCOUNT)
            }
            if (attempt < 3) Thread.sleep(500)
        }
        throw AuthException("注销请求已发送，但账号仍在线")
    }

    // ------------------------------------------------------------------
    // 认证请求（对应 portal.go 的 authenticate）
    // ------------------------------------------------------------------

    private fun authenticate(cfg: Config) {
        val chall = portalGet(
            cfg.host, "/cgi-bin/get_challenge",
            mapOf("username" to cfg.username, "ip" to "", "double_stack" to "1")
        )
        if (chall.optString("res") != "ok") {
            throw AuthException("获取 challenge 失败：${chall.optString("res", "未知错误")}")
        }
        val token = chall.optString("challenge")
        val ip = chall.optString("client_ip")
        if (token.length < 16 || ip.isEmpty()) throw AuthException("认证服务器 challenge 无效")

        val hmd5 = hmacMd5Hex(token, cfg.password)
        // info JSON：键按字母序排列（与 Go json.Marshal 的 map 序列化一致），
        // 字符串转义与 Go encoding/json 兼容（含 < > & 的 \u 转义）。
        val infoMap = linkedMapOf<String, String>()
        infoMap["acid"] = cfg.acId
        infoMap["enc_ver"] = "srun_bx1"
        infoMap["ip"] = ip
        infoMap["password"] = cfg.password
        infoMap["username"] = cfg.username
        val infoJson = infoMap.entries.joinToString(",", "{", "}") {
            "${goJsonString(it.key)}:${goJsonString(it.value)}"
        }
        val encoded = xEncode(infoJson.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
            ?: throw AuthException("无法编码认证请求")
        val info = "{SRBX1}" + quirkBase64(encoded)

        val q = linkedMapOf<String, String>()
        q["action"] = "login"
        q["ac_id"] = cfg.acId
        q["double_stack"] = "1"
        q["ip"] = ip
        q["n"] = "200"
        q["type"] = "1"
        q["username"] = cfg.username
        q["password"] = "{MD5}$hmd5"
        val sum = token + cfg.username + token + hmd5 +
            token + cfg.acId + token + ip + token + "200" + token + "1" + token + info
        q["info"] = info
        q["chksum"] = sha1Hex(sum)

        val resp = portalGet(cfg.host, "/cgi-bin/srun_portal", q)
        if (resp.optString("error") == "ok") return
        // 所有拒绝均为终止性失败：不做自动重试，避免锁定账号或热循环。
        val ecode = resp.optString("ecode")
        val rawError = resp.optString("error")
        val mapped = PortalErrors.map[ecode]
        // 优先展示错误码映射；否则展示服务器原始 error 字段；都没有则兜底。
        // 同时附带 ecode 和 host，方便排查 ac_id / 服务器地址是否配置正确。
        val detail = listOfNotNull(mapped, rawError.ifEmpty { null }).firstOrNull()
            ?: "认证被拒绝"
        val suffix = buildString {
            append("（服务器：").append(hostUrl(cfg.host)).append("，ac_id=").append(cfg.acId)
            if (ecode.isNotEmpty()) append("，ecode=").append(ecode)
            append("）")
        }
        throw AuthException(detail + suffix, ErrorKind.CREDENTIALS)
    }

    // ------------------------------------------------------------------
    // HTTP（JSONP）
    // ------------------------------------------------------------------

    private fun portalGet(host: String, path: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${formEnc(it.key)}=${formEnc(it.value)}" }
        val sep = if (query.isEmpty()) "" else "&"
        val url = hostUrl(host) + path + "?" + query + sep + "callback=" + CALLBACK
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw AuthException("认证服务器返回 HTTP $code")
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.trim()
            // 对齐上游 v1.1.1：部分 portal 版本对状态接口直接返回纯文本离线标记，
            // 仅在只读状态接口接受；登录/注销响应绝不能用纯文本蒙混过关。
            if (path == "/cgi-bin/rad_user_info" && body == "not_online_error") {
                return JSONObject().put("error", "not_online_error")
            }
            return JSONObject(stripJsonp(body))
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            throw AuthException("无法连接认证服务器 ${hostUrl(host)}（请确认已连上校园网）", ErrorKind.NETWORK)
        } finally {
            conn?.disconnect()
        }
    }

    private fun stripJsonp(raw: String): String {
        var t = raw.trim()
        val prefix = "$CALLBACK("
        if (t.startsWith(prefix)) {
            t = t.removePrefix(prefix)
            if (t.endsWith(";")) t = t.dropLast(1)
            if (t.endsWith(")")) t = t.dropLast(1)
        }
        return t.trim()
    }

    private fun formEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------
    // 账号匹配（对应 portal.go sameAccount；基础名区分大小写，域名忽略大小写）
    // ------------------------------------------------------------------

    private fun sameAccount(want: String, got: String, domain: String): Boolean {
        val (wb, wr) = splitAccount(want)
        var (gb, gr) = splitAccount(got)
        if (gr.isEmpty()) gr = domain.removePrefix("@")
        return wb.isNotEmpty() && wb == gb && (wr.isEmpty() || wr.equals(gr, ignoreCase = true))
    }

    private fun splitAccount(s: String): Pair<String, String> {
        val i = s.indexOf('@')
        return if (i >= 0) s.substring(0, i) to s.substring(i + 1) else s to ""
    }

    // ------------------------------------------------------------------
    // 密码学辅助
    // ------------------------------------------------------------------

    private fun hmacMd5Hex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacMD5"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ------------------------------------------------------------------
    // srun 私有编码：QuirkBase64 与 XEncode（XXTEA）
    // 移植自 libauth/coding.go，已用上游 coding_test.go 的全部 15 个向量验证。
    // ------------------------------------------------------------------

    private const val B64_ALPHABET =
        "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"

    fun quirkBase64(t: ByteArray): String {
        val a = t.size
        var len = a / 3 * 4
        if (a % 3 != 0) len += 4
        val u = CharArray(len)
        var ui = 0
        var o = 0
        while (o < a) {
            val p2 = t[o].toInt() and 0xFF
            val p1 = if (o + 1 < a) t[o + 1].toInt() and 0xFF else 0
            val p0 = if (o + 2 < a) t[o + 2].toInt() and 0xFF else 0
            val h = p2 shl 16 or (p1 shl 8) or p0
            for (i in 0 until 4) {
                u[ui++] = if (o * 8 + i * 6 > a * 8) '=' else B64_ALPHABET[(h ushr (6 * (3 - i))) and 0x3F]
            }
            o += 3
        }
        return String(u)
    }

    /** XXTEA 加密。str 非空时必返非空；保留可空返回以对齐上游语义。 */
    fun xEncode(str: ByteArray, key: ByteArray): ByteArray? {
        if (str.isEmpty()) return ByteArray(0)

        fun toWords(a: ByteArray, withLen: Boolean): IntArray {
            val v = IntArray((a.size + 3) / 4)
            var i = 0
            while (i < a.size) {
                var t = 0
                for (j in 0 until 4) {
                    if (i + j < a.size) t = t or ((a[i + j].toInt() and 0xFF) shl (j * 8))
                }
                v[i shr 2] = t
                i += 4
            }
            if (withLen) {
                val nv = IntArray(v.size + 1)
                System.arraycopy(v, 0, nv, 0, v.size)
                nv[v.size] = a.size
                return nv
            }
            return v
        }

        val v = toWords(str, true)
        val k = toWords(key, false)
        val n = v.size - 1
        var z = v[n]
        var y = v[0]
        var d = 0
        var q = 6 + 52 / (n + 1)
        while (q-- > 0) {
            d += DELTA // Int 溢出自动回绕，等价于 Go 的 uint32 加法
            val e = (d ushr 2) and 3
            for (p in 0..n) {
                y = if (p == n) v[0] else v[p + 1]
                val m = ((z ushr 5) xor (y shl 2)) +
                    (((y ushr 3) xor (z shl 4)) xor (d xor y)) +
                    (k[(p and 3) xor e] xor z)
                v[p] += m
                z = v[p]
            }
        }
        val out = ByteArray(v.size * 4)
        for (i in v.indices) {
            out[i * 4] = (v[i] and 0xFF).toByte()
            out[i * 4 + 1] = ((v[i] shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((v[i] shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((v[i] shr 24) and 0xFF).toByte()
        }
        return out
    }

    // ------------------------------------------------------------------
    // Go 兼容的 JSON 字符串转义（encoding/json 默认会转义 < > & 与 U+2028/9）
    // ------------------------------------------------------------------

    private fun goJsonString(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '<' -> sb.append("\\u003c")
                c == '>' -> sb.append("\\u003e")
                c == '&' -> sb.append("\\u0026")
                c == '\u2028' -> sb.append("\\u2028")
                c == '\u2029' -> sb.append("\\u2029")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
