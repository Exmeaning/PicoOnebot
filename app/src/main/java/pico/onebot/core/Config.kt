package pico.onebot.core

import android.content.Context
import android.system.Os
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
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
    private const val OWNER_RW_MODE = 0b110_000_000 // 0600

    enum class PasswordInitResult {
        OK,
        ALREADY_INITIALIZED,
        CORRUPT_RECOVERY_MODE,
        PERSIST_FAILED
    }

    enum class PasswordChangeResult {
        OK,
        NOT_INITIALIZED,
        INVALID_CURRENT,
        CORRUPT_RECOVERY_MODE,
        PERSIST_FAILED
    }

    data class NormalizedOrigin(val scheme: String, val host: String, val port: Int)

    @Volatile
    private var root: JSONObject = JSONObject()

    /** config.json 解析失败回退默认配置时置位:强制仅监听 127.0.0.1、禁用 picopico、拒绝覆盖磁盘配置。 */
    @Volatile
    var corruptRecoveryMode: Boolean = false
        private set

    @Volatile
    private var corruptRecoveryToken: String? = null

    @Volatile
    private var corruptFallbackSecrets: Map<String, Pair<String?, String?>> = emptyMap()

    @Volatile
    private var corruptRecoveredWebuiCreds: Pair<String, String>? = null

    @Volatile
    private var corruptSourceRawText: String? = null

    private val BASE64_STD_REGEX = Regex("^[A-Za-z0-9+/=]+$")
    private val CORRUPT_SALT_REGEX = Regex("\"passwordSalt\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val CORRUPT_HASH_REGEX = Regex("\"passwordHash\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    lateinit var dataDir: File
        private set

    private var file: File? = null

    /** 损坏恢复模式下返回待修复的原始配置文本(有界 UTF-8),供文件管理器在 dataDir/config.json 尚未落盘时预填充。 */
    fun corruptRecoveryRawSource(): String? = if (corruptRecoveryMode) corruptSourceRawText else null

    @Synchronized
    fun load(ctx: Context) {
        dataDir = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
        val internal = File(dataDir, "config.json")
        val external = File("/sdcard/$DIR_NAME/config.json")
        file = internal

        val hasInternal = internal.isFile
        val source: File? = when {
            hasInternal -> internal
            external.isFile -> external
            else -> null
        }

        if (source == null) {
            val fresh = migrateV1(JSONObject())
            fillDefaults(fresh)
            sanitizeWebuiDir(fresh)
            try {
                persist(fresh)
            } catch (t: Throwable) {
                PicoLog.e("config initial persist failed", t)
            }
            corruptRecoveryMode = false
            corruptRecoveryToken = null
            corruptFallbackSecrets = emptyMap()
            corruptRecoveredWebuiCreds = null
            corruptSourceRawText = null
            root = fresh
            PicoLog.i("config v2 initialized at " + internal.absolutePath)
            return
        }

        val rawText = try {
            source.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            PicoLog.e("config read failed: " + source.absolutePath, t)
            enterCorruptRecoveryMode(source, null)
            return
        }

        val parsed = try {
            JSONObject(rawText)
        } catch (t: Throwable) {
            PicoLog.e("config parse failed: " + source.absolutePath, t)
            enterCorruptRecoveryMode(source, rawText)
            return
        }

        val migratedFromV1 = parsed.optInt("version", 0) < 2
        val next = if (migratedFromV1) migrateV1(parsed) else JSONObject(parsed.toString())
        val beforeNormalize = next.toString()
        fillDefaults(next)
        val dirSanitized = sanitizeWebuiDir(next)
        val importedFromExternal = !hasInternal && source == external
        val needsPersist = importedFromExternal || migratedFromV1 || dirSanitized || next.toString() != beforeNormalize

        if (needsPersist) {
            try {
                persist(next)
            } catch (t: Throwable) {
                PicoLog.e("config persist failed during load", t)
            }
        } else {
            restrictPermissions(internal)
        }
        corruptRecoveryMode = false
        corruptRecoveryToken = null
        corruptFallbackSecrets = emptyMap()
        corruptRecoveredWebuiCreds = null
        corruptSourceRawText = null
        root = next
        if (importedFromExternal) {
            PicoLog.i("config v2 migrated one-time from /sdcard to " + internal.absolutePath)
            PicoLog.w("legacy plaintext config remains at " + external.absolutePath + "; please delete it after verifying migration")
        } else {
            PicoLog.i("config v2 loaded from " + internal.absolutePath)
            if (external.isFile) {
                PicoLog.w("unused legacy config still exists at " + external.absolutePath + "; please remove it to avoid keeping plaintext secrets on /sdcard")
            }
        }
    }

    private fun enterCorruptRecoveryMode(source: File, rawText: String?) {
        backupCorruptConfig(source)
        val fallback = migrateV1(JSONObject())
        fillDefaults(fallback)
        sanitizeWebuiDir(fallback)
        fallback.optJSONObject("webui")?.put("host", "127.0.0.1")
        val recoveryBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val recoveryToken = Base64.encodeToString(
            recoveryBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        corruptFallbackSecrets = if (rawText != null) extractCorruptConnectionSecrets(rawText) else emptyMap()
        corruptRecoveredWebuiCreds = if (rawText != null) extractCorruptWebuiCreds(rawText) else null
        corruptSourceRawText = rawText?.takeIf {
            it.isNotBlank() && it.indexOf('\u0000') < 0 && it.toByteArray(Charsets.UTF_8).size <= 1024 * 1024
        }
        corruptRecoveryToken = recoveryToken
        corruptRecoveryMode = true
        root = fallback
        // 直接写本地 logcat，绕过 PicoLog.mask() 且绝不进入 PicoLog.ring / /api/logs
        Log.w(
            PicoLog.TAG,
            "corrupt config recovery mode: one-time local recovery token=$recoveryToken " +
                "(log in at http://127.0.0.1:${webuiPort} and restore valid config.json via File Manager)"
        )
        PicoLog.w(
            "corrupt config recovery mode enabled (config.json NOT overwritten): " +
                "WebUI bound strictly to 127.0.0.1:${webuiPort}; " +
                "check adb logcat (${PicoLog.TAG}) for the one-time local recovery credential " +
                "and restore valid config.json via File Manager to exit recovery mode"
        )
    }

    private fun unescapeJsonString(raw: String): String? = try {
        JSONTokener("\"$raw\"").nextValue() as? String
    } catch (_: Throwable) {
        null
    }

    /** 返回从 [openQuoteIdx]('\"') 开始的 JSON 字符串闭合引号下标;未闭合或遇裸换行返回 -1。 */
    private fun findJsonStringEnd(s: String, openQuoteIdx: Int): Int {
        var i = openQuoteIdx + 1
        var escaped = false
        while (i < s.length) {
            val c = s[i]
            if (c == '\n' || c == '\r') return -1
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                return i
            }
            i++
        }
        return -1
    }

    private fun skipToLineEnd(s: String, from: Int, endExclusive: Int): Int {
        var i = from
        while (i < endExclusive && s[i] != '\n' && s[i] != '\r') i++
        return i
    }

    /** 提取单个 `{ ... }` 块内顶层(depth == 0)的 `"id"` / `"token"` / `"secret"` 字符串字段(反转义后)。 */
    private fun extractObjectSecretFields(raw: String, start: Int, endExclusive: Int): Map<String, String> {
        val fields = HashMap<String, String>(4)
        var idx = start + 1
        var depth = 0
        while (idx < endExclusive) {
            val c = raw[idx]
            if (c == '"') {
                val close = findJsonStringEnd(raw, idx)
                if (close < 0 || close >= endExclusive) {
                    idx = skipToLineEnd(raw, idx + 1, endExclusive)
                    continue
                }
                if (depth == 0) {
                    var look = close + 1
                    while (look < endExclusive && raw[look].isWhitespace()) look++
                    if (look < endExclusive && raw[look] == ':') {
                        val key = unescapeJsonString(raw.substring(idx + 1, close))
                        look++
                        while (look < endExclusive && raw[look].isWhitespace()) look++
                        if (look < endExclusive && raw[look] == '"') {
                            val valClose = findJsonStringEnd(raw, look)
                            if (valClose in (look + 1) until endExclusive) {
                                if (key == "id" || key == "token" || key == "secret") {
                                    val decoded = unescapeJsonString(raw.substring(look + 1, valClose))
                                    if (decoded != null) {
                                        fields[key] = decoded
                                    }
                                }
                                idx = valClose + 1
                                continue
                            }
                            idx = skipToLineEnd(raw, look + 1, endExclusive)
                            continue
                        }
                        idx = look
                        continue
                    }
                }
                idx = close + 1
                continue
            }
            when (c) {
                '{', '[' -> depth++
                '}', ']' -> if (depth > 0) depth-- else break
            }
            idx++
        }
        return fields
    }

    private fun collectConnectionSecret(
        raw: String,
        start: Int,
        endExclusive: Int,
        out: MutableMap<String, Pair<String?, String?>>
    ) {
        val fields = extractObjectSecretFields(raw, start, endExclusive)
        val id = fields["id"] ?: return
        if (id.isEmpty()) return
        val token = fields["token"]?.takeIf { it.isNotEmpty() && it != REDACTED_MASK }
        val secret = fields["secret"]?.takeIf { it.isNotEmpty() && it != REDACTED_MASK }
        if (token != null || secret != null) {
            out[id] = token to secret
        }
    }

    /** 尽量从损坏的 config.json 原始文本中提取连接 id -> (token, secret),供修复保存时还原 "***" 掩码。 */
    private fun extractCorruptConnectionSecrets(raw: String): Map<String, Pair<String?, String?>> {
        val out = HashMap<String, Pair<String?, String?>>()
        val braceStack = ArrayDeque<Int>()
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '"' -> {
                    val close = findJsonStringEnd(raw, i)
                    i = if (close >= 0) close + 1 else skipToLineEnd(raw, i + 1, raw.length)
                }
                '{' -> {
                    braceStack.addLast(i)
                    i++
                }
                '}' -> {
                    if (braceStack.isNotEmpty()) {
                        val start = braceStack.removeLast()
                        collectConnectionSecret(raw, start, i + 1, out)
                    }
                    i++
                }
                else -> i++
            }
        }
        if (braceStack.isNotEmpty()) {
            val start = braceStack.removeLast()
            collectConnectionSecret(raw, start, raw.length, out)
        }
        return out
    }

    /** 若损坏配置文件里保留了合法的 PBKDF2 盐与哈希,修复保存时继续沿用而非降级回未初始化状态。 */
    private fun extractCorruptWebuiCreds(raw: String): Pair<String, String>? {
        val saltRaw = CORRUPT_SALT_REGEX.find(raw)?.groupValues?.get(1) ?: return null
        val hashRaw = CORRUPT_HASH_REGEX.find(raw)?.groupValues?.get(1) ?: return null
        val salt = unescapeJsonString(saltRaw) ?: return null
        val hash = unescapeJsonString(hashRaw) ?: return null
        if (salt.length !in 16..128 || hash.length !in 32..128) return null
        if (!BASE64_STD_REGEX.matches(salt) || !BASE64_STD_REGEX.matches(hash)) return null
        return try {
            val saltBytes = Base64.decode(salt, Base64.NO_WRAP)
            val hashBytes = Base64.decode(hash, Base64.NO_WRAP)
            if (saltBytes.size == 16 && hashBytes.size == PASSWORD_BITS / 8) salt to hash else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun backupCorruptConfig(source: File) {
        try {
            val backup = File(dataDir, "config.json.corrupt." + System.currentTimeMillis())
            val bytes = source.readBytes()
            writePrivateBytes(backup, bytes)
            PicoLog.w("corrupt config backed up to " + backup.absolutePath)
        } catch (t: Throwable) {
            PicoLog.e("corrupt config backup failed", t)
        }
    }

    /** v1 -> v2。空对象也走这里,得到一份纯默认的 v2。 */
    private fun migrateV1(v1: JSONObject): JSONObject {
        if (v1.length() > 0) {
            try {
                val backup = File(dataDir, "config.v1.json")
                writePrivateBytes(backup, v1.toString(2).toByteArray(Charsets.UTF_8))
                PicoLog.i("v1 config backed up to " + backup.absolutePath)
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
                    .put("cors", false)
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
                .put("kernel_probe_delay_ms", v1.optLong("kernel_probe_delay_ms", 1000))
                .put("webui_dir", v1.optString("webui_dir", "webui"))
                // v1 的进程级行为字段,原样带过来(此前误放在每条连接里)
                .put("heartbeat", v1.optLong("heartbeat_interval", 5000))
                .put("messageFormat", v1.optString("message_format", "array"))
                .put("reportSelfMessage", v1.optBoolean("report_self_message", true))
        )
        return v2
    }

    private fun fillDefaults(target: JSONObject) {
        if (!target.has("version") || target.isNull("version")) target.put("version", 2)
        if (!target.has("enable") || target.isNull("enable")) target.put("enable", true)

        val webui = target.optJSONObject("webui") ?: JSONObject().also { target.put("webui", it) }
        if (!webui.has("host") || webui.isNull("host")) webui.put("host", "0.0.0.0")
        if (!webui.has("port") || webui.isNull("port")) webui.put("port", 6099)
        migratePlaintextPassword(webui)

        val net = target.optJSONObject("network") ?: JSONObject().also { target.put("network", it) }
        if (!net.has("wsServers") || net.isNull("wsServers")) net.put("wsServers", JSONArray())
        if (!net.has("wsClients") || net.isNull("wsClients")) net.put("wsClients", JSONArray())
        if (!net.has("httpServers") || net.isNull("httpServers")) net.put("httpServers", JSONArray())
        if (!net.has("httpClients") || net.isNull("httpClients")) net.put("httpClients", JSONArray())
        fillConnectionIds(net)
        // 老配置没有这两个开关:按"保持既有行为"补默认值 —— WebSocket 一直开着,
        // CORS 一直回 *,否则升级后行为会莫名其妙地变。
        fillConnectionFlags(net)

        val g = target.optJSONObject("general") ?: JSONObject().also { target.put("general", it) }
        if (!g.has("logLevel") || g.isNull("logLevel")) g.put("logLevel", "info")
        g.remove("showPanelOnStart")
        if (!g.has("forwardMode") || g.isNull("forwardMode")) g.put("forwardMode", "merge_one")
        if (!g.has("forwardMergeHeader") || g.isNull("forwardMergeHeader")) g.put("forwardMergeHeader", "〔合并转发〕")
        if (!g.has("mediaBaseUrl") || g.isNull("mediaBaseUrl")) g.put("mediaBaseUrl", "")
        if (!g.has("mediaBase64Limit") || g.isNull("mediaBase64Limit")) g.put("mediaBase64Limit", 4L * 1024 * 1024)
        if (!g.has("sendRatePerSec") || g.isNull("sendRatePerSec")) g.put("sendRatePerSec", 5)
        if (!g.has("kernelCallTimeoutMs") || g.isNull("kernelCallTimeoutMs")) g.put("kernelCallTimeoutMs", 30000)
        if (!g.has("reverse_reconnect_min_ms") || g.isNull("reverse_reconnect_min_ms")) g.put("reverse_reconnect_min_ms", 3000)
        if (!g.has("reverse_reconnect_max_ms") || g.isNull("reverse_reconnect_max_ms")) g.put("reverse_reconnect_max_ms", 60000)
        if (!g.has("post_timeout_ms") || g.isNull("post_timeout_ms")) g.put("post_timeout_ms", 10000)
        if (!g.has("post_queue_size") || g.isNull("post_queue_size")) g.put("post_queue_size", 1024)
        if (!g.has("event_queue_size") || g.isNull("event_queue_size")) g.put("event_queue_size", 8192)
        if (!g.has("kernel_probe_delay_ms") || g.isNull("kernel_probe_delay_ms")) g.put("kernel_probe_delay_ms", 1000)
        if (!g.has("webui_dir") || g.isNull("webui_dir")) g.put("webui_dir", "webui")
        if (!g.has("autoMarkRead") || g.isNull("autoMarkRead")) g.put("autoMarkRead", true)
        if (!g.has("readReportBatch") || g.isNull("readReportBatch")) g.put("readReportBatch", 10)
        if (!g.has("readReportIntervalSec") || g.isNull("readReportIntervalSec")) g.put("readReportIntervalSec", 60L)
        if (!g.has("readReportMinGapMs") || g.isNull("readReportMinGapMs")) g.put("readReportMinGapMs", 1500L)
        if (!g.has("readReportMaxPerMin") || g.isNull("readReportMaxPerMin")) g.put("readReportMaxPerMin", 18)
        if (!g.has("silenceSyncThresholdSec") || g.isNull("silenceSyncThresholdSec")) g.put("silenceSyncThresholdSec", 600L)
        if (!g.has("silenceSyncMaxAttempts") || g.isNull("silenceSyncMaxAttempts")) g.put("silenceSyncMaxAttempts", 3)
        // 进程级行为(原先误放在每条连接里,见 heartbeatInterval 的注释)。
        // 只在缺失时写入,不覆盖旧配置里已有的值。
        if (!g.has("heartbeat") || g.isNull("heartbeat")) {
            g.put("heartbeat", legacyServerField(target, "heartbeat") { it.optLong("heartbeat", 5000) } ?: 5000)
        }
        if (!g.has("reportSelfMessage") || g.isNull("reportSelfMessage")) {
            g.put("reportSelfMessage", legacyServerField(target, "reportSelfMessage") { it.optBoolean("reportSelfMessage", true) } ?: true)
        }
        if (!g.has("messageFormat") || g.isNull("messageFormat")) {
            g.put("messageFormat", legacyServerField(target, "messageFormat") { it.optString("messageFormat", "array") } ?: "array")
        }
    }

    /**
     * 从旧连接项里取一个即将迁移到 `general` 的字段。
     */
    private fun <T> legacyServerField(target: JSONObject, key: String, read: (JSONObject) -> T): T? {
        val arr = target.optJSONObject("network")?.optJSONArray("wsServers") ?: return null
        var fallback: T? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has(key) || o.isNull(key)) continue
            val value = read(o)
            if (o.optBoolean("enabled", true)) return value
            if (fallback == null) fallback = value
        }
        return fallback
    }

    @Synchronized
    fun save() {
        if (file == null) return
        if (corruptRecoveryMode) {
            PicoLog.w("config save skipped while in corrupt recovery mode")
            return
        }
        try {
            persist(root)
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

    val webuiHost: String
        get() {
            if (corruptRecoveryMode) return "127.0.0.1"
            return webui().let { w ->
                if (w.isNull("host")) "0.0.0.0" else w.optString("host", "0.0.0.0").ifBlank { "0.0.0.0" }
            }
        }

    val webuiPort: Int
        get() = webui().optInt("port", 6099).let { if (it in 1..65535) it else 6099 }

    /** 可选的 WebUI 显式跨源白名单(精确 origin 列表,默认空=仅同源)。 */
    val webuiCorsAllowedOrigins: List<String>
        get() {
            if (corruptRecoveryMode) return emptyList()
            val w = webui()
            val arr = w.optJSONArray("cors_allowed_origins") ?: w.optJSONArray("corsAllowedOrigins") ?: return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                if (arr.isNull(i)) continue
                val raw = arr.optString(i, "").trim().trimEnd('/')
                if (normalizeOrigin(raw, allowTrailingSlash = false) != null) {
                    out.add(raw)
                }
            }
            return out
        }

    /** 严格解析并规范化 http/https Origin(scheme + host + port,默认端口 80/443 归一化,不允许通配符/路径/userinfo)。 */
    fun normalizeOrigin(raw: String, allowTrailingSlash: Boolean = false): NormalizedOrigin? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "*" || trimmed.indexOf('\r') >= 0 || trimmed.indexOf('\n') >= 0) {
            return null
        }
        val uri = try {
            URI(trimmed)
        } catch (_: Throwable) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val rawPath = uri.rawPath ?: ""
        if (rawPath.isNotEmpty() && !(allowTrailingSlash && rawPath == "/")) return null
        val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT) ?: return null
        if (host.isEmpty() || host == "*") return null
        val port = when {
            uri.port > 0 -> {
                if (uri.port !in 1..65535) return null
                uri.port
            }
            uri.port == -1 -> if (scheme == "https") 443 else 80
            else -> return null
        }
        return NormalizedOrigin(scheme, host, port)
    }

    val webuiPasswordInitialized: Boolean
        get() {
            val w = webui()
            return w.optBoolean("passwordInitialized", false) &&
                !w.isNull("passwordSalt") && !w.isNull("passwordHash") &&
                w.optString("passwordSalt", "").isNotEmpty() && w.optString("passwordHash", "").isNotEmpty()
        }

    fun requiresPbkdf2ForVerification(): Boolean = !corruptRecoveryMode && webuiPasswordInitialized

    fun verifyWebuiPassword(password: String): Boolean {
        if (corruptRecoveryMode) {
            val expected = corruptRecoveryToken ?: return false
            if (expected.isEmpty()) return false
            return MessageDigest.isEqual(
                password.toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8)
            )
        }
        val snap = root
        val w = snap.optJSONObject("webui") ?: JSONObject()
        val initialized = w.optBoolean("passwordInitialized", false) &&
            !w.isNull("passwordSalt") && !w.isNull("passwordHash") &&
            w.optString("passwordSalt", "").isNotEmpty() && w.optString("passwordHash", "").isNotEmpty()
        if (!initialized) {
            return MessageDigest.isEqual(
                password.toByteArray(Charsets.UTF_8),
                DEFAULT_WEBUI_PASSWORD.toByteArray(Charsets.UTF_8)
            )
        }
        return try {
            val salt = Base64.decode(w.optString("passwordSalt"), Base64.NO_WRAP)
            val expected = Base64.decode(w.optString("passwordHash"), Base64.NO_WRAP)
            MessageDigest.isEqual(passwordHash(password, salt), expected)
        } catch (t: Throwable) {
            PicoLog.e("webui password verification failed", t)
            false
        }
    }

    /**
     * 首次初始化密码。已初始化后必须走 [changeWebuiPassword](需旧密码)。
     * 通过 [commitAction] 允许调用方在同一会话锁内原子发布 `root` 并推进 `sessionEpoch`/吊销会话。
     */
    @Synchronized
    fun initializeWebuiPassword(
        password: String,
        commitAction: (commitRoot: () -> Unit) -> Unit = { it() }
    ): PasswordInitResult {
        if (corruptRecoveryMode) return PasswordInitResult.CORRUPT_RECOVERY_MODE
        if (webuiPasswordInitialized) return PasswordInitResult.ALREADY_INITIALIZED
        val next = JSONObject(root.toString())
        val w = next.optJSONObject("webui") ?: JSONObject().also { next.put("webui", it) }
        storePasswordHash(w, password)
        return try {
            persist(next)
            commitAction { root = next }
            PasswordInitResult.OK
        } catch (t: Throwable) {
            PicoLog.e("webui password initialization persist failed", t)
            PasswordInitResult.PERSIST_FAILED
        }
    }

    /**
     * 修改已初始化的密码,**必须验证旧密码**。
     * 明确区分旧密码错误、损坏恢复模式与写盘失败,并通过 [commitAction] 原子发布新状态与吊销会话。
     */
    @Synchronized
    fun changeWebuiPassword(
        currentPassword: String,
        newPassword: String,
        commitAction: (commitRoot: () -> Unit) -> Unit = { it() }
    ): PasswordChangeResult {
        if (corruptRecoveryMode) return PasswordChangeResult.CORRUPT_RECOVERY_MODE
        if (!webuiPasswordInitialized) return PasswordChangeResult.NOT_INITIALIZED
        if (!verifyWebuiPassword(currentPassword)) return PasswordChangeResult.INVALID_CURRENT
        val next = JSONObject(root.toString())
        val w = next.optJSONObject("webui") ?: return PasswordChangeResult.PERSIST_FAILED
        storePasswordHash(w, newPassword)
        return try {
            persist(next)
            commitAction { root = next }
            PasswordChangeResult.OK
        } catch (t: Throwable) {
            PicoLog.e("webui password change persist failed", t)
            PasswordChangeResult.PERSIST_FAILED
        }
    }

    /** 写回 WebUI 提交的 AppConfig(webui.host/port/cors_allowed_origins + network + general),token 不在此改。 */
    @Synchronized
    fun applyAppConfig(patch: JSONObject) {
        check(!corruptRecoveryMode) {
            "当前处于损坏配置恢复模式，拒绝覆盖 config.json；请先在文件管理器中修复并保存 config.json"
        }
        val previous = root
        val next = JSONObject(previous.toString())
        val prevNetwork = previous.optJSONObject("network") ?: JSONObject()

        patch.optJSONObject("network")?.let { net ->
            val netCopy = JSONObject(net.toString())
            // 先还原 "***" 掩码,再整块替换 network —— 顺序反了就会把掩码写成真值
            restoreMaskedConnectionSecrets(netCopy, prevNetwork)
            val netErrors = validateNetwork(netCopy)
            require(netErrors.isEmpty()) { netErrors.joinToString("；") }
            next.put("network", netCopy)
        }
        patch.optJSONObject("general")?.let { g ->
            // 保留不在表单里的调优项
            val cur = next.optJSONObject("general") ?: JSONObject()
            val keys = g.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "webui_dir" || k == "webuiDir") {
                    val v = if (g.isNull(k)) "" else g.optString(k, "").trim()
                    require(isSafeWebuiDirValue(v)) { "general.webui_dir 必须位于 webui 目录内" }
                    cur.put("webui_dir", v)
                    cur.remove("webuiDir")
                } else {
                    cur.put(k, g.get(k))
                }
            }
            next.put("general", cur)
        }
        patch.optJSONObject("webui")?.let { w ->
            val webuiErrors = validateWebui(w)
            require(webuiErrors.isEmpty()) { webuiErrors.joinToString("；") }
            val cur = next.optJSONObject("webui") ?: JSONObject()
            if (w.has("host") && !w.isNull("host")) cur.put("host", w.optString("host").trim())
            if (w.has("port") && !w.isNull("port")) cur.put("port", w.optInt("port"))
            val corsKey = when {
                w.has("cors_allowed_origins") -> "cors_allowed_origins"
                w.has("corsAllowedOrigins") -> "corsAllowedOrigins"
                else -> null
            }
            if (corsKey != null) {
                cur.remove("corsAllowedOrigins")
                if (w.isNull(corsKey)) {
                    cur.put("cors_allowed_origins", JSONArray())
                } else {
                    cur.put("cors_allowed_origins", normalizeCorsOriginsArray(w.getJSONArray(corsKey)))
                }
            }
            next.put("webui", cur)
        }
        fillDefaults(next)
        sanitizeWebuiDir(next)
        persist(next)
        root = next
    }

    @Synchronized
    fun replaceConfig(
        candidate: JSONObject,
        commitAction: (commitRoot: () -> Unit) -> Unit = { it() }
    ) {
        require(candidate.optInt("version", 0) == 2) { "文件编辑仅支持 version: 2 配置" }
        val previous = root
        val next = JSONObject(candidate.toString())

        val webuiErrors = validateWebui(next.optJSONObject("webui"))
        require(webuiErrors.isEmpty()) { webuiErrors.joinToString("；") }
        normalizeWebuiCorsField(next.optJSONObject("webui"))

        validateWebuiDirField(next)

        // 先还原 "***" 掩码(含损坏配置恢复时的回退提取值),防止文件管理器保存把 OneBot token 覆写为字面量 "***"
        val nextNet = next.optJSONObject("network") ?: JSONObject().also { next.put("network", it) }
        val prevNet = previous.optJSONObject("network") ?: JSONObject()
        restoreMaskedConnectionSecrets(
            nextNet,
            prevNet,
            if (corruptRecoveryMode) corruptFallbackSecrets else emptyMap()
        )
        val netErrors = validateNetwork(nextNet)
        require(netErrors.isEmpty()) { netErrors.joinToString("；") }

        // 禁止通过原始配置文件编辑绕过改密流程或重置密码派生值
        preserveWebuiCredentials(previous, next)

        fillDefaults(next)
        sanitizeWebuiDir(next)
        persist(next)
        commitAction {
            root = next
            corruptRecoveryMode = false
            corruptRecoveryToken = null
            corruptFallbackSecrets = emptyMap()
            corruptRecoveredWebuiCreds = null
            corruptSourceRawText = null
        }
    }

    private fun normalizeCorsOriginsArray(arr: JSONArray): JSONArray {
        val out = JSONArray()
        val seen = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            if (arr.isNull(i)) continue
            val cleaned = arr.optString(i, "").trim().trimEnd('/')
            if (cleaned.isNotEmpty() && seen.add(cleaned)) {
                out.put(cleaned)
            }
        }
        return out
    }

    private fun normalizeWebuiCorsField(webui: JSONObject?) {
        if (webui == null) return
        val corsKey = when {
            webui.has("cors_allowed_origins") -> "cors_allowed_origins"
            webui.has("corsAllowedOrigins") -> "corsAllowedOrigins"
            else -> return
        }
        val raw = if (webui.isNull(corsKey)) null else webui.optJSONArray(corsKey)
        webui.remove("corsAllowedOrigins")
        webui.put("cors_allowed_origins", if (raw != null) normalizeCorsOriginsArray(raw) else JSONArray())
    }

    private fun preserveWebuiCredentials(previous: JSONObject, next: JSONObject) {
        val prevWebui = previous.optJSONObject("webui")
        val nextWebui = next.optJSONObject("webui") ?: JSONObject().also { next.put("webui", it) }
        nextWebui.remove("token")
        nextWebui.remove("tokenIsDefault")
        val recovered = if (corruptRecoveryMode) corruptRecoveredWebuiCreds else null
        val prevSalt = prevWebui?.takeIf { !it.isNull("passwordSalt") }?.optString("passwordSalt", "") ?: ""
        val prevHash = prevWebui?.takeIf { !it.isNull("passwordHash") }?.optString("passwordHash", "") ?: ""
        val prevInit = prevWebui?.optBoolean("passwordInitialized", false) == true &&
            prevSalt.isNotEmpty() && prevHash.isNotEmpty()
        when {
            prevInit -> {
                nextWebui.put("passwordInitialized", true)
                nextWebui.put("passwordSalt", prevSalt)
                nextWebui.put("passwordHash", prevHash)
            }
            recovered != null -> {
                nextWebui.put("passwordInitialized", true)
                nextWebui.put("passwordSalt", recovered.first)
                nextWebui.put("passwordHash", recovered.second)
            }
            else -> {
                nextWebui.put("passwordInitialized", false)
                nextWebui.remove("passwordSalt")
                nextWebui.remove("passwordHash")
            }
        }
    }

    internal fun validateWebui(webui: JSONObject?): List<String> {
        if (webui == null) return emptyList()
        val errors = ArrayList<String>()
        if (webui.has("host")) {
            val host = if (webui.isNull("host")) "" else webui.optString("host", "").trim()
            if (host.isEmpty()) errors.add("控制台监听地址不能为空")
        }
        if (webui.has("port")) {
            val port = if (webui.isNull("port")) -1 else webui.optInt("port", -1)
            if (port !in 1..65535) errors.add("控制台端口必须在 1-65535 之间")
        }
        for (key in arrayOf("cors_allowed_origins", "corsAllowedOrigins")) {
            if (!webui.has(key) || webui.isNull(key)) continue
            val arr = webui.optJSONArray(key)
            if (arr == null) {
                errors.add("webui.$key 必须是字符串数组")
                continue
            }
            for (i in 0 until arr.length()) {
                val entry = if (arr.isNull(i)) null else (arr.opt(i) as? String)
                if (entry == null || normalizeOrigin(entry, allowTrailingSlash = true) == null) {
                    errors.add("webui.$key[${i}] 不是合法的精确 Origin(仅支持 http:// 或 https://，不支持通配符或路径)")
                }
            }
        }
        return errors
    }

    private fun validateWebuiDirField(candidate: JSONObject) {
        for (rootKey in arrayOf("webui_dir", "webuiDir")) {
            if (candidate.has(rootKey)) {
                val v = if (candidate.isNull(rootKey)) "" else candidate.optString(rootKey, "").trim()
                require(isSafeWebuiDirValue(v)) { "general.webui_dir 必须位于 webui 目录内" }
                candidate.remove(rootKey)
            }
        }
        val g = candidate.optJSONObject("general") ?: return
        for (key in arrayOf("webui_dir", "webuiDir")) {
            if (g.has(key)) {
                val v = if (g.isNull(key)) "" else g.optString(key, "").trim()
                require(isSafeWebuiDirValue(v)) { "general.webui_dir 必须位于 webui 目录内" }
                if (key == "webuiDir") {
                    g.put("webui_dir", v)
                    g.remove("webuiDir")
                }
            }
        }
    }

    private fun sanitizeWebuiDir(target: JSONObject): Boolean {
        var changed = false
        for (rootKey in arrayOf("webui_dir", "webuiDir")) {
            if (target.has(rootKey)) {
                target.remove(rootKey)
                changed = true
            }
        }
        val g = target.optJSONObject("general") ?: return changed
        if (g.has("webuiDir")) {
            val v = if (g.isNull("webuiDir")) "" else g.optString("webuiDir", "").trim()
            g.remove("webuiDir")
            if (isSafeWebuiDirValue(v) && !g.has("webui_dir")) g.put("webui_dir", v)
            changed = true
        }
        val cur = if (g.isNull("webui_dir")) "" else g.optString("webui_dir", "webui").trim()
        if (!isSafeWebuiDirValue(cur)) {
            PicoLog.w("unsafe general.webui_dir rejected, falling back to 'webui'")
            g.put("webui_dir", "webui")
            changed = true
        }
        return changed
    }

    private fun isSafeWebuiDirValue(raw: String): Boolean {
        if (raw.isBlank() || raw.indexOf('\u0000') >= 0) return false
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith("/")) return false
        if (normalized.split('/').any { it == ".." }) return false
        if (!::dataDir.isInitialized) return normalized == "webui" || normalized.startsWith("webui/")
        return try {
            val base = File(dataDir, "webui").canonicalFile
            val candidate = File(dataDir, normalized).canonicalFile
            candidate.path == base.path || candidate.path.startsWith(base.path + File.separator)
        } catch (_: Throwable) {
            false
        }
    }

    private fun persist(snapshot: JSONObject) {
        val target = checkNotNull(file) { "配置文件尚未初始化" }
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            check(parent.mkdirs() || parent.isDirectory) { "无法创建配置目录: ${parent.absolutePath}" }
        }
        val temporary = File(parent, "${target.name}.${System.nanoTime()}.tmp")
        val bytes = snapshot.toString(2).toByteArray(Charsets.UTF_8)
        try {
            writePrivateBytes(temporary, bytes)
            if (!temporary.renameTo(target)) {
                throw IOException("无法原子替换配置文件: ${target.absolutePath}")
            }
            restrictPermissions(target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun writePrivateBytes(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { fos ->
            restrictPermissions(target)
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        restrictPermissions(target)
    }

    private fun restrictPermissions(f: File) {
        try {
            Os.chmod(f.path, OWNER_RW_MODE)
        } catch (_: Throwable) {
            try {
                f.setReadable(false, false)
                f.setWritable(false, false)
                f.setExecutable(false, false)
                f.setReadable(true, true)
                f.setWritable(true, true)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * WebUI 网络配置校验。
     */
    fun validateNetwork(candidate: JSONObject = network()): List<String> {
        val errors = ArrayList<String>()
        validateServers(candidate.optJSONArray("wsServers"), "正向 WebSocket", errors)
        validateClients(candidate.optJSONArray("wsClients"), "反向 WebSocket", setOf("ws", "wss"), errors)
        validateServers(candidate.optJSONArray("httpServers"), "HTTP 服务端", errors)
        validateClients(candidate.optJSONArray("httpClients"), "HTTP 上报", setOf("http", "https"), errors)
        return errors
    }

    private fun validateServers(arr: JSONArray?, kind: String, errors: MutableList<String>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val name = o.optString("name", "$kind ${i + 1}")
            val host = if (o.isNull("host")) "" else o.optString("host", "").trim()
            val port = if (o.isNull("port")) -1 else o.optInt("port", -1)
            val token = if (o.isNull("token")) "" else o.optString("token", "")
            if (host.isEmpty()) {
                errors.add("$name: 监听地址不能为空")
            } else if (!isLoopbackHost(host) && token.isBlank()) {
                errors.add("$name: 非回环监听地址必须填写 Access Token")
            }
            if (port !in 1..65535) {
                errors.add("$name: 端口必须在 1-65535 之间")
            }
        }
    }

    private fun validateClients(
        arr: JSONArray?,
        kind: String,
        allowedSchemes: Set<String>,
        errors: MutableList<String>
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val name = o.optString("name", "$kind ${i + 1}")
            val url = if (o.isNull("url")) "" else o.optString("url", "").trim()
            val token = if (o.isNull("token")) "" else o.optString("token", "")
            val uri = try { URI(url) } catch (_: Throwable) { null }
            val scheme = uri?.scheme?.lowercase(Locale.ROOT) ?: ""
            val host = uri?.host?.trim()?.removePrefix("[")?.removeSuffix("]") ?: ""
            if (uri == null || scheme !in allowedSchemes || host.isEmpty()) {
                val expected = allowedSchemes.joinToString("/") { "$it://" }
                errors.add("$name: 目标地址无效(必须以 $expected 开头且包含有效主机)")
            } else if (!isLoopbackHost(host) && token.isBlank()) {
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
    fun appConfigJson(): JSONObject {
        val snap = root
        val w = snap.optJSONObject("webui") ?: JSONObject()
        val host = if (corruptRecoveryMode) {
            "127.0.0.1"
        } else if (w.isNull("host")) {
            "0.0.0.0"
        } else {
            w.optString("host", "0.0.0.0").ifBlank { "0.0.0.0" }
        }
        val port = w.optInt("port", 6099).let { if (it in 1..65535) it else 6099 }
        val init = w.optBoolean("passwordInitialized", false) &&
            !w.isNull("passwordSalt") && !w.isNull("passwordHash") &&
            w.optString("passwordSalt", "").isNotEmpty() && w.optString("passwordHash", "").isNotEmpty()
        return JSONObject()
            .put(
                "webui",
                JSONObject()
                    .put("host", host)
                    .put("port", port)
                    .put("passwordInitialized", init)
                    .put("cors_allowed_origins", JSONArray(webuiCorsAllowedOrigins))
            )
            .put("network", JSONObject((snap.optJSONObject("network") ?: JSONObject()).toString()))
            .put("general", JSONObject((snap.optJSONObject("general") ?: JSONObject()).toString()))
    }

    /** [redactedRaw] 使用的掩码;回写时原样带回来表示"保持原值不动"。 */
    private const val REDACTED_MASK = "***"

    private val SECRET_CONNECTION_ARRAYS =
        arrayOf("wsServers", "wsClients", "httpServers", "httpClients")

    fun redactedRaw(): JSONObject {
        val snap = root
        val copy = JSONObject(snap.toString())
        copy.remove("access_token")
        copy.optJSONObject("webui")?.apply {
            val init = optBoolean("passwordInitialized", false) &&
                !isNull("passwordSalt") && !isNull("passwordHash") &&
                optString("passwordSalt", "").isNotEmpty() && optString("passwordHash", "").isNotEmpty()
            remove("token")
            remove("passwordSalt")
            remove("passwordHash")
            put("passwordInitialized", init)
        }
        copy.optJSONObject("network")?.let { net ->
            for (key in SECRET_CONNECTION_ARRAYS) {
                val arr = net.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.apply {
                        if (!isNull("token") && optString("token", "").isNotEmpty()) put("token", REDACTED_MASK)
                        if (!isNull("secret") && optString("secret", "").isNotEmpty()) put("secret", REDACTED_MASK)
                    }
                }
            }
        }
        return copy
    }

    /**
     * 把 `GET /api/pico/config` 或文件管理器回退脱敏下发过的 `"***"` 掩码原样还原成当前真值。
     */
    private fun restoreMaskedConnectionSecrets(
        incoming: JSONObject,
        current: JSONObject,
        corruptFallback: Map<String, Pair<String?, String?>> = emptyMap()
    ) {
        for (key in SECRET_CONNECTION_ARRAYS) {
            val inArr = incoming.optJSONArray(key) ?: continue
            val curArr = current.optJSONArray(key)
            val byId = HashMap<String, JSONObject>()
            if (curArr != null) {
                for (i in 0 until curArr.length()) {
                    val o = curArr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isNotEmpty()) byId[id] = o
                }
            }
            for (i in 0 until inArr.length()) {
                val item = inArr.optJSONObject(i) ?: continue
                val id = item.optString("id", "")
                val old = byId[id]
                val fallbackPair = corruptFallback[id]
                for (field in arrayOf("token", "secret")) {
                    if (!item.isNull(field) && item.optString(field, "") == REDACTED_MASK) {
                        val oldValue = old?.takeIf { !it.isNull(field) }?.optString(field, "")
                            ?.takeIf { it.isNotEmpty() }
                            ?: (if (field == "token") fallbackPair?.first else fallbackPair?.second)
                            ?: ""
                        if (oldValue.isNotEmpty()) {
                            item.put(field, oldValue)
                        } else {
                            PicoLog.w("connection " + id.ifEmpty { "?" } + ": '" + field + "' 是掩码且无可还原的旧值,按原样保存")
                        }
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

    val heartbeatInterval: Long
        get() = generalLongOrServer("heartbeat", "heartbeat", 5000)

    val reportSelfMessage: Boolean
        get() = generalBoolOrServer("reportSelfMessage", "reportSelfMessage", true)

    val messageFormat: String
        get() = generalStrOrServer("messageFormat", "messageFormat", "array")

    private fun generalLongOrServer(generalKey: String, serverKey: String, def: Long): Long {
        val g = general()
        if (g.has(generalKey) && !g.isNull(generalKey)) return g.optLong(generalKey, def)
        val s = primaryWsServer()
        return if (s.has(serverKey) && !s.isNull(serverKey)) s.optLong(serverKey, def) else def
    }

    private fun generalBoolOrServer(generalKey: String, serverKey: String, def: Boolean): Boolean {
        val g = general()
        if (g.has(generalKey) && !g.isNull(generalKey)) return g.optBoolean(generalKey, def)
        val s = primaryWsServer()
        return if (s.has(serverKey) && !s.isNull(serverKey)) s.optBoolean(serverKey, def) else def
    }

    private fun generalStrOrServer(generalKey: String, serverKey: String, def: String): String {
        val g = general()
        if (g.has(generalKey) && !g.isNull(generalKey)) return g.optString(generalKey, def)
        val s = primaryWsServer()
        return if (s.has(serverKey) && !s.isNull(serverKey)) s.optString(serverKey, def) else def
    }

    val kernelTimeoutMs: Long get() = long("kernelCallTimeoutMs", 30000).coerceAtLeast(1000L)
    val eventQueueSize: Int get() = int("event_queue_size", 8192).coerceIn(16, 65536)
    val sendRatePerSec: Int get() = int("sendRatePerSec", 5)
    val autoMarkRead: Boolean get() = bool("autoMarkRead", true)
    val readReportBatch: Int get() = int("readReportBatch", 10).coerceAtLeast(1)
    val readReportIntervalMs: Long get() = long("readReportIntervalSec", 60L).coerceAtLeast(5L) * 1000L
    val readReportMinGapMs: Long get() = long("readReportMinGapMs", 1500L).coerceAtLeast(500L)
    val readReportMaxPerMin: Int get() = int("readReportMaxPerMin", 18).coerceIn(1, 60)
    val silenceSyncThresholdMs: Long
        get() {
            val sec = long("silenceSyncThresholdSec", 600L)
            return if (sec <= 0L) 0L else sec.coerceAtLeast(60L) * 1000L
        }
    val silenceSyncMaxAttempts: Int get() = int("silenceSyncMaxAttempts", 3).coerceIn(0, 10)
    val webuiDir: File
        get() {
            val base = File(dataDir, "webui")
            val raw = str("webui_dir", "webui").trim()
            if (!isSafeWebuiDirValue(raw)) return base
            return try {
                val baseCanon = base.canonicalFile
                val target = File(dataDir, raw.replace('\\', '/')).canonicalFile
                if (target.path == baseCanon.path || target.path.startsWith(baseCanon.path + File.separator)) {
                    target
                } else {
                    baseCanon
                }
            } catch (_: Throwable) {
                base
            }
        }
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
            val u = if (o.isNull("url")) "" else o.optString("url", "")
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

    private fun optNonNull(obj: JSONObject, key: String): Any? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.opt(key)
        return if (v == null || v === JSONObject.NULL) null else v
    }

    private fun lookup(key: String): Any? {
        val snap = root
        val g = snap.optJSONObject("general")
        if (g != null) {
            optNonNull(g, key)?.let { return it }
            val camel = toCamel(key)
            if (camel != key) optNonNull(g, camel)?.let { return it }
            val snake = toSnake(key)
            if (snake != key) optNonNull(g, snake)?.let { return it }
        }
        return optNonNull(snap, key)
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
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            if (v.isNull(i)) null else v.optString(i, "").ifEmpty { null }
        }
        else -> emptyList()
    }

    private fun fillConnectionIds(net: JSONObject) {
        val seen = HashSet<String>()
        for (key in arrayOf("wsServers", "wsClients", "httpServers", "httpClients")) {
            val arr = net.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = if (o.isNull("id")) "" else o.optString("id")
                if (id.isEmpty() || !seen.add(id)) {
                    var fresh = newId()
                    while (!seen.add(fresh)) fresh = newId()
                    o.put("id", fresh)
                }
            }
        }
    }

    private fun fillConnectionFlags(net: JSONObject) {
        fun fill(key: String, cors: Boolean, websocket: Boolean) {
            val arr = net.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.has("cors") || o.isNull("cors")) o.put("cors", cors)
                if (!o.has("websocket") || o.isNull("websocket")) o.put("websocket", websocket)
            }
        }
        fill("wsServers", cors = false, websocket = true)
        fill("httpServers", cors = false, websocket = false)
    }

    private fun newId(): String = java.lang.Long.toHexString(
        System.nanoTime() xor (Math.random() * Long.MAX_VALUE).toLong()
    ).take(8)

    private fun migratePlaintextPassword(webui: JSONObject) {
        val hasSalt = !webui.isNull("passwordSalt") && webui.optString("passwordSalt", "").isNotEmpty()
        val hasHash = !webui.isNull("passwordHash") && webui.optString("passwordHash", "").isNotEmpty()
        if (hasSalt && hasHash) {
            webui.put("passwordInitialized", true)
        } else {
            val legacy = if (webui.isNull("token")) "" else webui.optString("token", "")
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
        val derived = passwordHash(password, salt)
        webui.put("passwordSalt", Base64.encodeToString(salt, Base64.NO_WRAP))
        webui.put("passwordHash", Base64.encodeToString(derived, Base64.NO_WRAP))
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
