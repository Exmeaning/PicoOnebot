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
                    // v1 的这些是进程级语义,归入 general(见下方 general 段)
                    .put("cors", true)
                    .put("websocket", true)
            )
        }

        val wsClients = JSONArray()
        for (url in flatUrls(v1.opt("reverse_ws"))) {
            wsClients.put(
                JSONObject()
                    .put("id", newId()).put("name", "反向 WS").put("enabled", true)
                    .put("url", url).put("token", v1.optString("access_token", ""))
                    .put("reconnectInterval", v1.optLong("reverse_reconnect_min_ms", 3000))
            )
        }

        val httpClients = JSONArray()
        for (url in flatUrls(v1.opt("post_url"))) {
            httpClients.put(
                JSONObject()
                    .put("id", newId()).put("name", "HTTP 上报").put("enabled", true)
                    .put("url", url).put("token", v1.optString("access_token", ""))
                    .put("secret", v1.optString("post_secret", ""))
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
                // v1 的进程级行为字段,原样带过来(此前误放在每条连接里)
                .put("heartbeat", v1.optLong("heartbeat_interval", 5000))
                .put("messageFormat", v1.optString("message_format", "array"))
                .put("reportSelfMessage", v1.optBoolean("report_self_message", true))
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
        fillConnectionIds(net)
        // 老配置没有这两个开关:按"保持既有行为"补默认值 —— WebSocket 一直开着,
        // CORS 一直回 *,否则升级后行为会莫名其妙地变。
        fillConnectionFlags(net)

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
        // 进程级行为(原先误放在每条连接里,见 heartbeatInterval 的注释)。
        // 只在缺失时写入,不覆盖旧配置里已有的值。
        if (!g.has("heartbeat")) g.put("heartbeat", 5000)
        if (!g.has("reportSelfMessage")) g.put("reportSelfMessage", true)
        if (!g.has("messageFormat")) g.put("messageFormat", "array")
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

    /** 首次初始化密码。已初始化后必须走 [changeWebuiPassword](需旧密码)。 */
    @Synchronized
    fun initializeWebuiPassword(password: String): Boolean {
        if (webuiPasswordInitialized) return false
        val w = root.optJSONObject("webui") ?: JSONObject().also { root.put("webui", it) }
        storePasswordHash(w, password)
        save()
        return true
    }

    /**
     * 修改已初始化的密码,**必须验证旧密码**。
     *
     * docs/config/webui.md 承诺可在控制台修改密码,而实现原先只允许首次设置,
     * 忘记密码后没有任何自救路径(配置文件里只有 PBKDF2 派生值)。
     *
     * @return true 已修改;false 旧密码不正确或尚未初始化(后者应走 initialize)。
     */
    @Synchronized
    fun changeWebuiPassword(currentPassword: String, newPassword: String): Boolean {
        if (!webuiPasswordInitialized) return false
        if (!verifyWebuiPassword(currentPassword)) return false
        val w = root.optJSONObject("webui") ?: return false
        storePasswordHash(w, newPassword)
        save()
        return true
    }

    /** 写回 WebUI 提交的 AppConfig(webui.host/port + network + general),token 不在此改。 */
    @Synchronized
    fun applyAppConfig(patch: JSONObject) {
        val previous = root
        root = JSONObject(previous.toString())
        try {
            // 先还原 "***" 掩码,再整块替换 network —— 顺序反了就会把掩码写成真值
            patch.optJSONObject("network")?.let { net ->
                restoreMaskedConnectionSecrets(net)
                root.put("network", net)
            }
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

    /** [redactedRaw] 使用的掩码;回写时原样带回来表示"保持原值不动"。 */
    private const val REDACTED_MASK = "***"

    private val SECRET_CONNECTION_ARRAYS =
        arrayOf("wsServers", "wsClients", "httpServers", "httpClients")

    fun redactedRaw(): JSONObject {
        val copy = JSONObject(root.toString())
        copy.remove("access_token")
        copy.optJSONObject("webui")?.apply {
            remove("token")
            remove("passwordSalt")
            remove("passwordHash")
            put("passwordInitialized", webuiPasswordInitialized)
        }
        // 连接项里的 token / secret 与 webui 密码同级敏感:这个对象会经
        // GET /api/pico/config 与 _pico_config 直接下发给调用方,原样返回等于把
        // 所有 Access Token 明文交出去。
        copy.optJSONObject("network")?.let { net ->
            for (key in SECRET_CONNECTION_ARRAYS) {
                val arr = net.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.apply {
                        if (optString("token", "").isNotEmpty()) put("token", REDACTED_MASK)
                        if (optString("secret", "").isNotEmpty()) put("secret", REDACTED_MASK)
                    }
                }
            }
        }
        return copy
    }

    /**
     * 把 `GET /api/pico/config` 下发过的掩码原样还原成当前真值。
     *
     * 客户端常见的用法是"取回配置 → 改其中几项 → 整份 PUT 回来"。若不还原,
     * 掩码 `***` 会被当成真 token 落盘,下次启动整条连接就鉴权失败了。
     * 按连接项 `id` 对齐;新条目没有 id 匹配就保留客户端给的值。
     */
    private fun restoreMaskedConnectionSecrets(incoming: JSONObject) {
        val current = network()
        for (key in SECRET_CONNECTION_ARRAYS) {
            val inArr = incoming.optJSONArray(key) ?: continue
            val curArr = current.optJSONArray(key) ?: continue
            val byId = HashMap<String, JSONObject>()
            for (i in 0 until curArr.length()) {
                val o = curArr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isNotEmpty()) byId[id] = o
            }
            for (i in 0 until inArr.length()) {
                val item = inArr.optJSONObject(i) ?: continue
                val old = byId[item.optString("id", "")] ?: continue
                for (field in arrayOf("token", "secret")) {
                    if (item.optString(field, "") == REDACTED_MASK) {
                        item.put(field, old.optString(field, ""))
                    }
                }
            }
        }
    }

    // ---------------- 兼容访问器(签名不变) ----------------

    val enabled: Boolean get() = root.optBoolean("enable", true)
    val host: String get() = primaryWsServer().optString("host", "0.0.0.0")
    val port: Int get() = primaryWsServer().optInt("port", 3001)
    val accessToken: String get() = primaryWsServer().optString("token", "")

    /**
     * 以下三项是**进程级**行为,不是连接级。
     *
     * WebUI 曾把它们渲染在每条连接的编辑表单里,但实现只读 `primaryWsServer()`
     * —— 反向 WS 部署下该数组为空,于是一律落默认值:关不掉自身消息上报、
     * `messageFormat=string` 无效、`heartbeat: 0` 也关不掉。现在明确改为
     * `general` 级配置,并回落到旧连接项里的值以兼容老配置文件。
     */
    val heartbeatInterval: Long
        get() = generalLongOrServer("heartbeat", "heartbeat", 5000)

    val reportSelfMessage: Boolean
        get() = generalBoolOrServer("reportSelfMessage", "reportSelfMessage", true)

    val messageFormat: String
        get() = generalStrOrServer("messageFormat", "messageFormat", "array")

    private fun generalLongOrServer(generalKey: String, serverKey: String, def: Long): Long {
        val g = general()
        if (g.has(generalKey)) return g.optLong(generalKey, def)
        val s = primaryWsServer()
        return if (s.has(serverKey)) s.optLong(serverKey, def) else def
    }

    private fun generalBoolOrServer(generalKey: String, serverKey: String, def: Boolean): Boolean {
        val g = general()
        if (g.has(generalKey)) return g.optBoolean(generalKey, def)
        val s = primaryWsServer()
        return if (s.has(serverKey)) s.optBoolean(serverKey, def) else def
    }

    private fun generalStrOrServer(generalKey: String, serverKey: String, def: String): String {
        val g = general()
        if (g.has(generalKey)) return g.optString(generalKey, def)
        val s = primaryWsServer()
        return if (s.has(serverKey)) s.optString(serverKey, def) else def
    }

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

    /**
     * 每个连接项都必须有**唯一**的 id:运行时的监听/反向连接表按 id 索引,
     * WebUI 也按 id 回报真实状态。手写配置文件漏了 id 或重复了,状态就会张冠李戴,
     * 所以在这里补齐(WebUI 新建时自带 id,这条只兜手写的底)。
     */
    private fun fillConnectionIds(net: JSONObject) {
        val seen = HashSet<String>()
        for (key in arrayOf("wsServers", "wsClients", "httpServers", "httpClients")) {
            val arr = net.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty() || !seen.add(id)) {
                    var fresh = newId()
                    while (!seen.add(fresh)) fresh = newId()
                    o.put("id", fresh)
                }
            }
        }
    }

    /**
     * 补齐监听项的 `cors` / `websocket` 开关。
     *
     * 老配置里没有这两个字段。缺省必须与**升级前实际行为**一致,否则用户在
     * 没做任何操作的情况下会发现 CORS 头没了、或者 WS 连不上:
     *   - wsServers:两者一直开启
     *   - httpServers:CORS 一直开启;WS 升级此前也是接受的(同一 HttpServer)
     */
    private fun fillConnectionFlags(net: JSONObject) {
        fun fill(key: String, cors: Boolean, websocket: Boolean) {
            val arr = net.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.has("cors")) o.put("cors", cors)
                if (!o.has("websocket")) o.put("websocket", websocket)
            }
        }
        fill("wsServers", cors = true, websocket = true)
        fill("httpServers", cors = true, websocket = true)
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
