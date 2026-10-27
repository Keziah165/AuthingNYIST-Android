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
 * NYIST ??? srun(??)????? Kotlin ???
 *
 * ??? GUYU2233/AuthingNYIST(internal/app/portal.go ? libauth ?),
 * ???????? Palvef/AuthingNYIST,???? z4yx/GoAuthing?
 * ????? GPL-3.0 ????
 */
object Srun {

    const val DEFAULT_HOST = "https://auth.nyist.edu.cn"
    const val DEFAULT_AC_ID = "1"
    const val NETWORK_TEC = "nyist-tec"
    const val NETWORK_STU = "nyist-stu"

    private const val CALLBACK = "authing_callback"
    private const val USER_AGENT = "AuthingNYIST-Android"
    private const val TIMEOUT_MS = 12_000
    private const val DELTA = -1640531527 // 0x9E3779B9 ? 32 ??????

    /** ????:NETWORK ??????;CREDENTIALS / OTHER_ACCOUNT ??????? */
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
    // ????(?? config.go Validate)
    // ------------------------------------------------------------------

    fun hostUrl(h: String): String {
        val s = if (!h.contains("://")) "https://$h" else h
        return s.trimEnd('/')
    }

    fun validate(cfg: Config) {
        require(cfg.network == NETWORK_TEC || cfg.network == NETWORK_STU) { "????? $NETWORK_TEC ? $NETWORK_STU" }
        if (cfg.username.isNotEmpty()) {
            require(cfg.username.trim() == cfg.username && cfg.username.none { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) {
                "??????????"
            }
            require(cfg.username.count { it == '@' } <= 1 && !cfg.username.startsWith("@") && !cfg.username.endsWith("@")) {
                "?????????"
            }
        }
        require(cfg.password.none { it == '\r' || it == '\n' || it == '\u0000' }) { "????????" }
        val u = runCatching { URI(hostUrl(cfg.host)) }.getOrNull()
        require(u != null && u.host != null && (u.scheme == "http" || u.scheme == "https") &&
            u.userInfo == null && (u.path == null || u.path == "/" || u.path.isEmpty()) &&
            u.query == null && u.fragment == null
        ) { "??????????? HTTP(S) ??,??????????" }
        require((cfg.acId.toIntOrNull() ?: 0) > 0) { "acId ??????" }
    }

    // ------------------------------------------------------------------
    // ????(?? portal.go ? Check / Connect / Disconnect)
    // ------------------------------------------------------------------

    /** ??????? */
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
                Status(same, user, ip, if (same) "???" else "??????", r.optString("domain"))
            }
            err == "not_online_error" || err == "not_online" || err == "offline" ->
                Status(false, "", ip, "??????")
            else -> throw AuthException("???????????????")
        }
    }

    /**
     * ????(???? v1.1.1 ? InspectStatus,?????????/??):
     * ?????????????????????????????????????
     * Android ??? SSID(??????),Wi-Fi ???????????
     *
     * @param hasNetwork ????? ConnectivityManager ??????????
     */
    fun inspectStatus(cfg: Config, hasNetwork: Boolean): Status {
        validate(cfg)
        if (!hasNetwork) {
            return Status(false, "", "", "?????,?????? Wi-Fi ?????")
        }
        return try {
            check(cfg)
        } catch (e: Srun.AuthException) {
            if (e.kind == ErrorKind.CREDENTIALS || e.kind == ErrorKind.OTHER_ACCOUNT) throw e
            throw AuthException("?????,??????????:${e.message}", ErrorKind.NETWORK)
        }
    }

    /**
     * ??(??)?????????????,?????????
     * Android ???? Wi-Fi,???????????? Wi-Fi?
     */
    fun connect(cfg: Config): Status {
        validate(cfg)
        require(cfg.hasCredentials()) { "?????????" }
        val s = check(cfg)
        if (!s.online && s.username.isNotEmpty()) {
            throw AuthException("?????????(${s.username}),????????????", ErrorKind.OTHER_ACCOUNT)
        }
        if (s.online) return s
        authenticate(cfg)
        val after = check(cfg)
        if (!after.online) throw AuthException("?????????????", ErrorKind.NETWORK)
        return after
    }

    /**
     * ??(??)???????????????
     * ???? v1.1.1 / ??????:???????????? IP ????,
     * ??? challenge???? password/info/chksum;?????????????
     */
    fun disconnect(cfg: Config) {
        validate(cfg)
        require(cfg.username.isNotEmpty()) { "??????" }
        val s = check(cfg)
        if (s.username.isEmpty()) return // ????,????
        if (!s.online) {
            throw AuthException("??????????(${s.username}),????", ErrorKind.OTHER_ACCOUNT)
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
            throw AuthException("????????????,???????")
        }
        // ???????:????????????
        for (attempt in 0 until 4) {
            val st = check(cfg)
            if (st.username.isEmpty()) return
            if (!st.online) {
                throw AuthException("??????????(${st.username}),????", ErrorKind.OTHER_ACCOUNT)
            }
            if (attempt < 3) Thread.sleep(500)
        }
        throw AuthException("???????,??????")
    }

    // ------------------------------------------------------------------
    // ????(?? portal.go ? authenticate)
    // ------------------------------------------------------------------

    private fun authenticate(cfg: Config) {
        val chall = portalGet(
            cfg.host, "/cgi-bin/get_challenge",
            mapOf("username" to cfg.username, "ip" to "", "double_stack" to "1")
        )
        if (chall.optString("res") != "ok") {
            throw AuthException("?? challenge ??:${chall.optString("res", "????")}")
        }
        val token = chall.optString("challenge")
        val ip = chall.optString("client_ip")
        if (token.length < 16 || ip.isEmpty()) throw AuthException("????? challenge ??")

        val hmd5 = hmacMd5Hex(token, cfg.password)
        // info JSON:???????(? Go json.Marshal ? map ?????),
        // ?????? Go encoding/json ??(? < > & ? \u ??)?
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
            ?: throw AuthException("????????")
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
        // ???????????:??????,???????????
        val ecode = resp.optString("ecode")
        val rawError = resp.optString("error")
        val mapped = PortalErrors.map[ecode]
        // ?????????;????????? error ??;???????
        // ???? ecode ? host,???? ac_id / ????????????
        val detail = listOfNotNull(mapped, rawError.ifEmpty { null }).firstOrNull()
            ?: "?????"
        val suffix = buildString {
            append("(???:").append(hostUrl(cfg.host)).append(",ac_id=").append(cfg.acId)
            if (ecode.isNotEmpty()) append(",ecode=").append(ecode)
            append(")")
        }
        throw AuthException(detail + suffix, ErrorKind.CREDENTIALS)
    }

    // ------------------------------------------------------------------
    // HTTP(JSONP)
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
                throw AuthException("??????? HTTP $code")
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.trim()
            // ???? v1.1.1:?? portal ??????????????????,
            // ??????????;??/????????????????
            if (path == "/cgi-bin/rad_user_info" && body == "not_online_error") {
                return JSONObject().put("error", "not_online_error")
            }
            return JSONObject(stripJsonp(body))
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            throw AuthException("????????? ${hostUrl(host)}(?????????)", ErrorKind.NETWORK)
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
    // ????(?? portal.go sameAccount;????????,???????)
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
    // ?????
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
    // srun ????:QuirkBase64 ? XEncode(XXTEA)
    // ??? libauth/coding.go,???? coding_test.go ??? 15 ??????
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

    /** XXTEA ???str ???????;?????????????? */
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
            d += DELTA // Int ??????,??? Go ? uint32 ??
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
    // Go ??? JSON ?????(encoding/json ????? < > & ? U+2028/9)
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
