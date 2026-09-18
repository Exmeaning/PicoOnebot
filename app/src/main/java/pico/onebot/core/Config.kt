package pico.onebot.core

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * ```
 * { "version":2, "enable":true,
 *   "webui":   { host, port, passwordInitialized, passwordSalt, passwordHash },
 *   "network": { wsServers[], wsClients[], httpServers[], httpClients[] },
 *   "general": { logLevel, forwardMode, ... } }
 * ```
 */
object Config {

    private const val DIR_NAME = "pico-onebot"
    const val DEFAULT_WEBUI_PASSWORD = "picopico"
    private const val PASSWORD_ITERATIONS = 120_000
    private const val PASSWORD_BITS = 256

    @Volatile
    private var root: JSONObject = JSONObject()

    lateinit var dataDir: File
        private set

    private var file: File? = null

    fun load(ctx: Context) {
        dataDir = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
        val external = File("/sdcard/$DIR_NAME/config.json")
        val internal = File(dataDir, "config.json")
        val f = if (external.isFile) external else internal
        file = f
        val raw = try {
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        } catch (t: Throwable) {
            PicoLog.e("config parse failed, using defaults: " + f.absolutePath, t)
            JSONObject()
        }
        root = if (raw.optInt("version", 0) >= 2) raw else migrateV1(raw, f)
        fillDefaults()
        save()
        PicoLog.i("config v2 loaded from " + f.absolutePath)
    }

    /** v1 -> v2。空对象也走这里,得到一份纯默认的 v2。 */
    private fun migrateV1(v1: JSONObject, f: File): JSONObject {
        if (v1.length() > 0) {
            try {
                File(f.parentFile, "config.v1.json").writeText(v1.toString(2))
                PicoLog.i("v1 config backed up to config.v1.json")
            } catch (t: Throwable) {
                PicoLog.w("v1 backup failed: " + t.message)
            }
        }
        val v2 = JSONObject()
        v2.put("version", 2)
        v2.put("enable", v1.optBoolean("enable", true))

        v2.put(
            "webui", JSONObject()
                .put("host", "0.0.0.0")
                .put("port", 6099)
                .put("passwordInitialized", false)
        )

        val wsServers = JSONArray()
        if (v1.length() > 0) {
            wsServers.put(
                JSONObject()
                    .put("id", newId())
                    .put("name", "主正向 WS")
                    .put("enabled", true)
                    .put("host", v1.optString("host", "0.0.0.0"))
                    .put("port", v1.optInt("port", 3001))
                    .put("token", v1.optString("access_token", ""))
                    .put("heartbeat", v1.optLong("heartbeat_interval", 5000))
                    .put("messageFormat", v1.optString("message_format", "array"))
                    .put("reportSelfMessage", v1.optBoolean("report_self_message", true))
                    .put("debug", false)
            )
        }

        val wsClients = JSONArray()
        for (url in flatUrls(v1.opt("reverse_ws"))) {
            wsClients.put(
                JSONObject()
                    .put("id", newId()).put("name", "反向 WS").put("enabled", true)
                    .put("url", url).put("token", v1.optString("access_token", ""))
                    .put("heartbeat", v1.optLong("heartbeat_interval", 5000))
                    .put("reconnectInterval", v1.optLong("reverse_reconnect_min_ms", 3000))
                    .put("messageFormat", v1.optString("message_format", "array"))
                    .put("reportSelfMessage", v1.optBoolean("report_self_message", true))
                    .put("debug", false)
            )
        }

        val httpClients = JSONArray()
        for (url in flatUrls(v1.opt("post_url"))) {
            httpClients.put(
                JSONObject()
                    .put("id", newId()).put("name", "HTTP 上报").put("enabled", true)
                    .put("url", url).put("token", v1.optString("access_token", ""))
                    .put("secret", v1.optString("post_secret", ""))
                    .put("heartbeat", 0)
                    .put("messageFormat", v1.optString("message_format", "array"))
                    .put("reportSelfMessage", v1.optBoolean("report_self_message", true))
                    .put("debug", false)
            )
        }

        v2.put(
            "network", JSONObject()
                .put("wsServers", wsServers)
                .put("wsClients", wsClients)
                .put("httpServers", JSONArray())
                .put("httpClients", httpClients)
        )

        v2.put(
            "general", JSONObject()
                .put("logLevel", "info")
                .put("forwardMode", v1.optString("forward_mode", "merge_one"))
                .put("forwardMergeHeader", v1.optString("forward_merge_header", "〔合并转发〕"))
                .put("mediaBaseUrl", v1.optString("media_base_url", ""))
                .put("mediaBase64Limit", v1.optLong("media_base64_limit", 4L * 1024 * 1024))
                .put("sendRatePerSec", v1.optInt("send_rate_per_sec", 5))
                .put("kernelCallTimeoutMs", v1.optLong("kernel_call_timeout_ms", 30000))
                .put("reverse_reconnect_min_ms", v1.optLong("reverse_reconnect_min_ms", 3000))
                .put("reverse_reconnect_max_ms", v1.optLong("reverse_reconnect_max_ms", 60000))
                .put("post_timeout_ms", v1.optInt("post_timeout_ms", 10000))
                .put("post_queue_size", v1.optInt("post_queue_size", 1024))
                .put("event_queue_size", v1.optInt("event_queue_size", 8192))
                .put("kernel_probe_delay_ms", v1.optLong("kernel_probe_delay_ms", 5000))
                .put("webui_dir", v1.optString("webui_dir", "webui"))
        )
        return v2
    }

    private fun fillDefaults() {
        if (!root.has("version")) root.put("version", 2)
        if (!root.has("enable")) root.put("enable", true)

        val webui = root.optJSONObject("webui") ?: JSONObject().also { root.put("webui", it) }
        if (!webui.has("host")) webui.put("host", "0.0.0.0")
        if (!webui.has("port")) webui.put("port", 6099)
        migratePlaintextPassword(webui)

        val net = root.optJSONObject("network") ?: JSONObject().also { root.put("network", it) }
        if (!net.has("wsServers")) net.put("wsServers", JSONArray())
        if (!net.has("wsClients")) net.put("wsClients", JSONArray())
        if (!net.has("httpServers")) net.put("httpServers", JSONArray())
        if (!net.has("httpClients")) net.put("httpClients", JSONArray())

        val g = root.optJSONObject("general") ?: JSONObject().also { root.put("general", it) }
        if (!g.has("logLevel")) g.put("logLevel", "info")
        g.remove("showPanelOnStart")
        if (!g.has("forwardMode")) g.put("forwardMode", "merge_one")
        if (!g.has("forwardMergeHeader")) g.put("forwardMergeHeader", "〔合并转发〕")
        if (!g.has("mediaBaseUrl")) g.put("mediaBaseUrl", "")
        if (!g.has("mediaBase64Limit")) g.put("mediaBase64Limit", 4L * 1024 * 1024)
        if (!g.has("sendRatePerSec")) g.put("sendRatePerSec", 5)
        if (!g.has("kernelCallTimeoutMs")) g.put("kernelCallTimeoutMs", 30000)
        if (!g.has("reverse_reconnect_min_ms")) g.put("reverse_reconnect_min_ms", 3000)
        if (!g.has("reverse_reconnect_max_ms")) g.put("reverse_reconnect_max_ms", 60000)
        if (!g.has("post_timeout_ms")) g.put("post_timeout_ms", 10000)
        if (!g.has("post_queue_size")) g.put("post_queue_size", 1024)
        if (!g.has("event_queue_size")) g.put("event_queue_size", 8192)
        if (!g.has("kernel_probe_delay_ms")) g.put("kernel_probe_delay_ms", 5000)
        if (!g.has("webui_dir")) g.put("webui_dir", "webui")
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(root.toString(2))
        } catch (t: Throwable) {
            PicoLog.e("config save failed", t)
        }
    }

    fun raw(): JSONObject = root

    // ---------------- v2 结构直取 ----------------

    fun webui(): JSONObject = root.optJSONObject("webui") ?: JSONObject()
    fun network(): JSONObject = root.optJSONObject("network") ?: JSONObject()
    fun general(): JSONObject = root.optJSONObject("general") ?: JSONObject()

    fun wsServers(): JSONArray = network().optJSONArray("wsServers") ?: JSONArray()
    fun wsClients(): JSONArray = network().optJSONArray("wsClients") ?: JSONArray()
    fun httpServers(): JSONArray = network().optJSONArray("httpServers") ?: JSONArray()
    fun httpClients(): JSONArray = network().optJSONArray("httpClients") ?: JSONArray()

    /** 第一个(优先 enabled)正向 WS 配置项 —— 老的 host/port/token 访问器的落点。 */
    private fun primaryWsServer(): JSONObject {
        val arr = wsServers()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("enabled", true)) return o
        }
        return arr.optJSONObject(0) ?: JSONObject()
    }

    val webuiHost: String get() = webui().optString("host", "0.0.0.0")
    val webuiPort: Int get() = webui().optInt("port", 6099)
    val webuiPasswordInitialized: Boolean get() {
        val w = webui()
        return w.optBoolean("passwordInitialized", false) &&
            w.optString("passwordSalt", "").isNotEmpty() && w.optString("passwordHash", "").isNotEmpty()
    }
    fun verifyWebuiPassword(password: String): Boolean {
        if (!webuiPasswordInitialized) {
            return MessageDigest.isEqual(
                password.toByteArray(Charsets.UTF_8),
                DEFAULT_WEBUI_PASSWORD.toByteArray(Charsets.UTF_8)
            )
        }
        val w = webui()
        return try {
            val salt = Base64.decode(w.optString("passwordSalt"), Base64.NO_WRAP)
            val expected = Base64.decode(w.optString("passwordHash"), Base64.NO_WRAP)
            MessageDigest.isEqual(passwordHash(password, salt), expected)
        } catch (t: Throwable) {
            PicoLog.e("webui password verification failed", t)
            false
        }
    }

    /** 只允许首次初始化；后续不提供修改或从配置文件找回明文密码的入口。 */
    @Synchronized
    fun initializeWebuiPassword(password: String): Boolean {
        if (webuiPasswordInitialized) return false
        val w = root.optJSONObject("webui") ?: JSONObject().also { root.put("webui", it) }
        storePasswordHash(w, password)
        save()
        return true
    }

    /** 写回 WebUI 提交的 AppConfig(webui.host/port + network + general),token 不在此改。 */
    @Synchronized
    fun applyAppConfig(patch: JSONObject) {
        val previous = root
        root = JSONObject(previous.toString())
        try {
            patch.optJSONObject("network")?.let { root.put("network", it) }
            patch.optJSONObject("general")?.let { g ->
                // 保留不在表单里的调优项
                val cur = root.optJSONObject("general") ?: JSONObject()
                val keys = g.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    cur.put(k, g.get(k))
                }
                root.put("general", cur)
            }
            patch.optJSONObject("webui")?.let { w ->
                val cur = root.optJSONObject("webui") ?: JSONObject()
                if (w.has("host")) cur.put("host", w.get("host"))
                if (w.has("port")) cur.put("port", w.get("port"))
                root.put("webui", cur)
            }
            fillDefaults()
            persist()
        } catch (error: Throwable) {
            root = previous
            throw error
        }
    }

    @Synchronized
    fun replaceConfig(candidate: JSONObject) {
        require(candidate.optInt("version", 0) == 2) { "文件编辑仅支持 version: 2 配置" }
        val errors = validateNetwork(candidate.optJSONObject("network") ?: JSONObject())
        require(errors.isEmpty()) { errors.joinToString("；") }
        val previous = root
        root = JSONObject(candidate.toString())
        try {
            fillDefaults()
            persist()
        } catch (error: Throwable) {
            root = previous
            throw error
        }
    }

    private fun persist() {
        val target = checkNotNull(file) { "配置文件尚未初始化" }
        val temporary = File(target.parentFile, target.name + ".tmp")
        try {
            temporary.writeText(root.toString(2), Charsets.UTF_8)
            check(temporary.renameTo(target)) { "无法替换配置文件" }
        } finally {
            temporary.delete()
        }
    }

    /**
     * WebUI
     */
    fun validateNetwork(candidate: JSONObject = network()): List<String> {
        val errors = ArrayList<String>()
        validateServers(candidate.optJSONArray("wsServers"), "正向 WebSocket", errors)
        validateClients(candidate.optJSONArray("wsClients"), "反向 WebSocket", errors)
        validateServers(candidate.optJSONArray("httpServers"), "HTTP 服务端", errors)
        validateClients(candidate.optJSONArray("httpClients"), "HTTP 上报", errors)
        return errors
    }

    private fun validateServers(arr: JSONArray?, kind: String, errors: MutableList<String>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val name = o.optString("name", "$kind ${i + 1}")
            val host = o.optString("host", "").trim()
            if (host.isEmpty()) {
                errors.add("$name: 监听地址不能为空")
            } else if (!isLoopbackHost(host) && o.optString("token", "").isBlank()) {
                errors.add("$name: 非回环监听地址必须填写 Access Token")
            }
        }
    }

    private fun validateClients(arr: JSONArray?, kind: String, errors: MutableList<String>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val name = o.optString("name", "$kind ${i + 1}")
            val url = o.optString("url", "").trim()
            val host = try { URI(url).host ?: "" } catch (_: Throwable) { "" }
            if (host.isEmpty()) {
                errors.add("$name: 目标地址无效")
            } else if (!isLoopbackHost(host) && o.optString("token", "").isBlank()) {
                errors.add("$name: 非回环目标地址必须填写 Access Token")
            }
        }
    }

    fun isLoopbackHost(value: String): Boolean {
        val host = value.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1") return true
        if (host.startsWith("127.")) {
            val parts = host.split('.')
            return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }
        return host == "0:0:0:0:0:0:0:1" || host.startsWith("::ffff:127.")
    }

    /** GET /api/config 不暴露密码派生值。 */
    fun appConfigJson(): JSONObject = JSONObject()
        .put(
            "webui",
            JSONObject()
                .put("host", webuiHost)
                .put("port", webuiPort)
                .put("passwordInitialized", webuiPasswordInitialized)
        )
        .put("network", JSONObject(network().toString()))
        .put("general", JSONObject(general().toString()))

    fun redactedRaw(): JSONObject {
        val copy = JSONObject(root.toString())
        copy.remove("access_token")
        copy.optJSONObject("webui")?.apply {
            remove("token")
            remove("passwordSalt")
            remove("passwordHash")
            put("passwordInitialized", webuiPasswordInitialized)
        }
        return copy
    }

    // ---------------- 兼容访问器(签名不变) ----------------

    val enabled: Boolean get() = root.optBoolean("enable", true)
    val host: String get() = primaryWsServer().optString("host", "0.0.0.0")
    val port: Int get() = primaryWsServer().optInt("port", 3001)
    val accessToken: String get() = primaryWsServer().optString("token", "")
    val heartbeatInterval: Long get() = primaryWsServer().optLong("heartbeat", 5000)
    val reportSelfMessage: Boolean get() = primaryWsServer().optBoolean("reportSelfMessage", true)
    val messageFormat: String get() = primaryWsServer().optString("messageFormat", "array")

    val kernelTimeoutMs: Long get() = long("kernelCallTimeoutMs", 30000)
    val eventQueueSize: Int get() = int("event_queue_size", 8192)
    val sendRatePerSec: Int get() = int("sendRatePerSec", 5)
    val webuiDir: File get() = File(dataDir, str("webui_dir", "webui"))
    /** WebUI 文件管理器以当前生效配置文件所在的 pico-onebot 目录为根。 */
    val fileManagerDir: File get() = file?.parentFile ?: dataDir
    val activeConfigFile: File? get() = file

    /** 反向 WS 地址列表(enabled)。 */
    fun reverseUrls(): List<String> = enabledUrls(wsClients())

    /** HTTP 上报地址列表(enabled)。 */
    fun postUrls(): List<String> = enabledUrls(httpClients())

    private fun enabledUrls(arr: JSONArray): List<String> {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val u = o.optString("url", "")
            if (u.isNotEmpty()) out.add(u)
        }
        return out
    }

    // ---------------- 通用取值器:general(原名/驼峰/蛇形)→ 根 ----------------

    fun bool(key: String, def: Boolean): Boolean = (lookup(key) as? Boolean) ?: def
    fun int(key: String, def: Int): Int = numberOf(lookup(key))?.toInt() ?: def
    fun long(key: String, def: Long): Long = numberOf(lookup(key))?.toLong() ?: def
    fun str(key: String, def: String): String {
        val v = lookup(key) ?: return def
        return if (v is String) v else v.toString()
    }

    private fun numberOf(v: Any?): Number? = when (v) {
        is Number -> v
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun lookup(key: String): Any? {
        val g = root.optJSONObject("general")
        if (g != null) {
            if (g.has(key)) return g.opt(key)
            val camel = toCamel(key)
            if (camel != key && g.has(camel)) return g.opt(camel)
            val snake = toSnake(key)
            if (snake != key && g.has(snake)) return g.opt(snake)
        }
        if (root.has(key)) return root.opt(key)
        return null
    }

    private fun toCamel(s: String): String {
        if (!s.contains('_')) return s
        val sb = StringBuilder()
        var up = false
        for (c in s) {
            if (c == '_') { up = true; continue }
            sb.append(if (up) c.uppercaseChar() else c); up = false
        }
        return sb.toString()
    }

    private fun toSnake(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c.isUpperCase()) sb.append('_').append(c.lowercaseChar()) else sb.append(c)
        }
        return sb.toString()
    }

    private fun flatUrls(v: Any?): List<String> = when (v) {
        is String -> if (v.isEmpty()) emptyList() else listOf(v)
        is JSONArray -> (0 until v.length()).mapNotNull { i -> v.optString(i, "").ifEmpty { null } }
        else -> emptyList()
    }

    private fun newId(): String = java.lang.Long.toHexString(
        System.nanoTime() xor (Math.random() * Long.MAX_VALUE).toLong()
    ).take(8)

    private fun migratePlaintextPassword(webui: JSONObject) {
        if (webui.optString("passwordSalt", "").isNotEmpty() &&
            webui.optString("passwordHash", "").isNotEmpty()) {
            webui.put("passwordInitialized", true)
        } else {
            val legacy = webui.optString("token", "")
            if (legacy.isNotEmpty() && legacy != DEFAULT_WEBUI_PASSWORD) {
                storePasswordHash(webui, legacy)
            } else {
                webui.put("passwordInitialized", false)
                webui.remove("passwordSalt")
                webui.remove("passwordHash")
            }
        }
        webui.remove("token")
        webui.remove("tokenIsDefault")
    }

    private fun storePasswordHash(webui: JSONObject, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        webui.put("passwordSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
        webui.put("passwordHash", Base64.encodeToString(passwordHash(password, salt), Base64.NO_WRAP))
        webui.put("passwordInitialized", true)
        webui.remove("token")
        webui.remove("tokenIsDefault")
    }

    private fun passwordHash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, PASSWORD_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
