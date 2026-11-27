package pico.onebot.net

import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pico.onebot.PicoBoot
import pico.onebot.action.ActionRouter
import pico.onebot.core.Config
import pico.onebot.core.ContainerRestart
import pico.onebot.core.Counters
import pico.onebot.core.NetUtil
import pico.onebot.core.PicoLog
import pico.onebot.event.MetaEvents
import pico.onebot.kernel.ContactStore
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.MsgWatchdog
import pico.onebot.kernel.PicoLoginManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.Locale

/**
 * WebUI 后端(端口 6099 的 `/api/…`)。契约见 docs/design §5.1,前端 `web/src/lib/api.ts` 为准。
 *
 * 登录成功后签发进程内会话令牌；后续请求使用 `Authorization: Bearer <session>`。
 * 运维脚本仍可在 Authorization 头中直接使用密码，浏览器不会把密码保存到 localStorage。
 * 日志流 `WS /api/logs/stream?token=` 仅接受已签发的会话令牌(在 [HttpServer] 里校验)。
 */
object WebApi {

    private const val MAX_EDIT_BYTES = 1024 * 1024L
    private const val SESSION_CAP = 64
    private const val SESSION_IDLE_TTL_NS = 24L * 3600L * 1_000_000_000L
    private const val SESSION_ABS_TTL_NS = 7L * 24L * 3600L * 1_000_000_000L
    const val MAX_LOG_STREAMS = 16

    // 鉴权失败限流与指数退避参数(单调时钟)
    private const val MAX_AUTH_TRACKED_IPS = 256
    private const val AUTH_FREE_FAILURES = 4
    private const val AUTH_DECAY_NS = 15L * 60L * 1_000_000_000L
    private const val AUTH_MAX_LOCKOUT_MS = 300_000L
    private const val GLOBAL_PBKDF2_WINDOW_NS = 60L * 1_000_000_000L
    private const val GLOBAL_PBKDF2_MAX_FAILS = 20

    private data class SessionEntry(val createdAtNanos: Long, var lastSeenAtNanos: Long)
    private data class LogStreamEntry(val conn: WsConn, val sessionToken: String?)
    private data class AuthFailureState(
        var failures: Int = 0,
        var hasLockout: Boolean = false,
        var lockedUntilNanos: Long = 0L,
        var lastFailureNanos: Long = 0L
    )

    internal data class AuthCheck(
        val authorized: Boolean,
        val sessionToken: String? = null,
        val rateLimited: Boolean = false,
        val retryAfterSec: Long = 0L,
        val authEpoch: Long = 0L
    )

    /**
     * 进程内会话令牌。用 LinkedHashMap(插入序)而不是并发 Set:
     * 达到上限时淘汰最老的一条,同时支持滑动/绝对 TTL 与日志流联动吊销。
     */
    private val sessions = LinkedHashMap<String, SessionEntry>(64, 0.75f, false)
    private val logStreams = LinkedHashSet<LogStreamEntry>()
    private val sessionsLock = Any()
    private var sessionEpoch: Long = 0L

    private val authFailures = LinkedHashMap<String, AuthFailureState>(64, 0.75f, true)
    private val globalFailWindow = ArrayDeque<Long>(GLOBAL_PBKDF2_MAX_FAILS + 1)
    private val authRateLock = Any()

    private val secureRandom = SecureRandom()
    private val fileWriteLock = Any()

    class Response(val status: Int, val json: JSONObject)

    internal fun <T> withConfigWriteLock(block: () -> T): T = synchronized(fileWriteLock) { block() }

    /** 校验 general.webui_dir 是否严格位于 dataDir/webui 内部。 */
    internal fun isSafeWebuiDir(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.indexOf('\u0000') >= 0) return false
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) return false
        val normalized = trimmed.replace('\\', '/')
        if (normalized.split('/').any { it == ".." }) return false
        return try {
            val base = File(Config.dataDir, "webui").canonicalFile
            val target = File(Config.dataDir, normalized).canonicalFile
            target.path == base.path || target.path.startsWith(base.path + File.separator)
        } catch (_: Throwable) {
            false
        }
    }

    /** 统一校验 WebUI/Admin 配置补丁的 network、webui.host/port 与 general.webui_dir。 */
    internal fun validateConfigPatch(
        patch: JSONObject,
        validateNetworkFallback: Boolean = true
    ): List<String> {
        val candidateNet = patch.optJSONObject("network") ?: if (validateNetworkFallback) Config.network() else null
        val errors = if (candidateNet != null) Config.validateNetwork(candidateNet).toMutableList() else mutableListOf()
        patch.optJSONObject("webui")?.let { w ->
            if (w.has("host")) {
                if (w.isNull("host") || w.optString("host", "").trim().isEmpty()) {
                    errors.add("控制台监听地址不能为空")
                }
            }
            if (w.has("port")) {
                val p = if (w.isNull("port")) -1 else w.optInt("port", -1)
                if (p !in 1..65535) errors.add("控制台端口必须在 1-65535 之间")
            }
        }
        fun checkWebuiDirHolder(obj: JSONObject?) {
            if (obj == null) return
            for (k in arrayOf("webui_dir", "webuiDir")) {
                if (obj.has(k)) {
                    val v = if (obj.isNull(k)) "" else obj.optString(k, "")
                    if (!isSafeWebuiDir(v)) {
                        errors.add("不允许将 general.$k 修改到 webui 目录之外")
                    }
                }
            }
        }
        checkWebuiDirHolder(patch.optJSONObject("general"))
        checkWebuiDirHolder(patch)
        return errors
    }

    internal fun isKnownRoute(method: String, path: String): Boolean = when {
        path == "/api/auth/login" && method == "POST" -> true
        path == "/api/status" && method == "GET" -> true
        path == "/api/connections" && method == "GET" -> true
        path == "/api/config" && (method == "GET" || method == "PUT") -> true
        path == "/api/files" && method == "GET" -> true
        path == "/api/files/content" && (method == "GET" || method == "PUT") -> true
        path == "/api/logs" && method == "GET" -> true
        path == "/api/restart" && method == "POST" -> true
        path == "/api/logout" && method == "POST" -> true
        path == "/api/account/logout" && method == "POST" -> true
        path == "/api/webui/token" && method == "PUT" -> true
        path == "/api/qr" && method == "GET" -> true
        path == "/api/qr/refresh" && method == "POST" -> true
        else -> false
    }

    internal fun requiresJsonBody(method: String, path: String): Boolean = when {
        path == "/api/auth/login" && method == "POST" -> true
        path == "/api/config" && method == "PUT" -> true
        path == "/api/files/content" && method == "PUT" -> true
        path == "/api/webui/token" && method == "PUT" -> true
        else -> false
    }

    fun handle(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray,
        clientIp: String = "unknown",
        preAuthorized: Boolean = false
    ): Response {
        // 登录不需要先有票据
        if (path == "/api/auth/login" && method == "POST") return login(body, clientIp)

        // HTTP 路由仅接受 Authorization 头,不接受 ?token= 查询串认证(防 CSRF 与日志泄露);
        // 若 HttpServer 读体前已完成预鉴权则直接复用,绝不对单次请求执行两次 PBKDF2。
        if (!preAuthorized) {
            val auth = checkAuthorization(headers, query, clientIp, allowQuerySession = false)
            if (auth.rateLimited) {
                return error(429, "认证尝试过于频繁，请 ${auth.retryAfterSec} 秒后再试")
            }
            if (!auth.authorized) {
                return Response(401, JSONObject().put("ok", false).put("message", "unauthorized"))
            }
        }
        if (!Config.webuiPasswordInitialized &&
            !(path == "/api/webui/token" && method == "PUT") &&
            !(path == "/api/logout" && method == "POST")
        ) {
            return Response(
                428,
                JSONObject().put("ok", false)
                    .put("mustChangePassword", true)
                    .put("message", "首次使用必须先修改控制台密码")
            )
        }

        return try {
            when {
                path == "/api/status" && method == "GET" -> Response(200, status())
                path == "/api/connections" && method == "GET" -> arrayResponse(connections())
                path == "/api/config" && method == "GET" -> Response(200, Config.appConfigJson())
                path == "/api/config" && method == "PUT" -> putConfig(body)
                path == "/api/files" && method == "GET" -> listFiles(query)
                path == "/api/files/content" && method == "GET" -> readFile(query)
                path == "/api/files/content" && method == "PUT" -> putFile(body)
                path == "/api/logs" && method == "GET" -> arrayResponse(logs(query))
                path == "/api/restart" && method == "POST" -> restart()
                path == "/api/logout" && method == "POST" -> logout(headers, query)
                path == "/api/account/logout" && method == "POST" -> Response(200, accountLogout())
                path == "/api/webui/token" && method == "PUT" -> setToken(body, clientIp)
                path == "/api/qr" && method == "GET" -> Response(200, qr(false))
                path == "/api/qr/refresh" && method == "POST" -> Response(200, qr(true))
                else -> Response(404, JSONObject().put("ok", false).put("message", "unknown route: $path"))
            }
        } catch (_: JSONException) {
            error(400, "请求内容不是合法 JSON")
        } catch (t: Throwable) {
            PicoLog.e("webapi $path failed", t)
            Response(500, JSONObject().put("ok", false).put("message", "内部服务错误"))
        }
    }

    /** 前端把 JSON 数组直接反序列化,包一层给 HttpServer 用 `_array` 标记原样输出。 */
    private fun arrayResponse(arr: JSONArray): Response =
        Response(200, JSONObject().put("_array", arr))

    /**
     * 兼容公开方法:仅通过 Authorization 头校验(一般 HTTP 路由禁止 `?token=` 查串认证),
     * 单请求最多执行一次 PBKDF2。日志流 WS 在 [HttpServer] 中显式开启 `allowQuerySession = true`。
     */
    fun authorized(headers: Map<String, String>, query: Map<String, String>): Boolean =
        checkAuthorization(headers, query, "unknown", allowQuerySession = false).authorized

    internal fun checkAuthorization(
        headers: Map<String, String>,
        query: Map<String, String>,
        clientIp: String,
        allowQuerySession: Boolean
    ): AuthCheck {
        val headerCred = extractBearer(headers["authorization"])
        val queryCred = if (allowQuerySession) (query["token"] ?: "") else ""

        // 1. 优先 O(1) 校验已签发的会话令牌(零 PBKDF2 开销)
        if (headerCred.isNotEmpty()) {
            val epoch = lookupSessionEpoch(headerCred)
            if (epoch != null) {
                return AuthCheck(authorized = true, sessionToken = headerCred, authEpoch = epoch)
            }
        }
        if (queryCred.isNotEmpty()) {
            val epoch = lookupSessionEpoch(queryCred)
            if (epoch != null) {
                return AuthCheck(authorized = true, sessionToken = queryCred, authEpoch = epoch)
            }
        }

        // 2. 仅允许 Authorization 头回退校验密码(兼容运维脚本),且单请求最多跑一次 PBKDF2 并受限流保护;
        //    绝不对 ?token= 查询串运行 PBKDF2。
        if (headerCred.isNotEmpty()) {
            val waitSec = checkAuthRateLimit(clientIp)
            if (waitSec > 0L) {
                return AuthCheck(authorized = false, rateLimited = true, retryAfterSec = waitSec)
            }
            val epochBefore = synchronized(sessionsLock) { sessionEpoch }
            val initBefore = Config.webuiPasswordInitialized
            if (Config.verifyWebuiPassword(headerCred)) {
                val stillValid = synchronized(sessionsLock) {
                    sessionEpoch == epochBefore && Config.webuiPasswordInitialized == initBefore
                }
                if (!stillValid) {
                    return AuthCheck(authorized = false)
                }
                recordAuthSuccess(clientIp)
                return AuthCheck(authorized = true, sessionToken = null, authEpoch = epochBefore)
            }
            recordAuthFailure(clientIp)
        }
        return AuthCheck(authorized = false)
    }

    private fun extractBearer(authHeader: String?): String {
        val auth = authHeader ?: return ""
        return if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7) else auth
    }

    private fun checkAuthRateLimit(clientIp: String): Long {
        val now = System.nanoTime()
        synchronized(authRateLock) {
            pruneGlobalFailuresLocked(now)
            if (globalFailWindow.size >= GLOBAL_PBKDF2_MAX_FAILS) {
                val oldest = globalFailWindow.first()
                val remNs = ((oldest + GLOBAL_PBKDF2_WINDOW_NS) - now).coerceAtLeast(1_000_000_000L)
                return (remNs + 999_999_999L) / 1_000_000_000L
            }
            val state = authFailures[clientIp] ?: return 0L
            if ((now - state.lastFailureNanos) > AUTH_DECAY_NS) {
                authFailures.remove(clientIp)
                return 0L
            }
            if (state.hasLockout) {
                val remNs = state.lockedUntilNanos - now
                if (remNs > 0L) {
                    return ((remNs + 999_999_999L) / 1_000_000_000L).coerceAtLeast(1L)
                }
                state.hasLockout = false
            }
            return 0L
        }
    }

    private fun recordAuthFailure(clientIp: String) {
        val now = System.nanoTime()
        synchronized(authRateLock) {
            pruneGlobalFailuresLocked(now)
            globalFailWindow.addLast(now)
            val state = authFailures[clientIp] ?: AuthFailureState().also {
                while (authFailures.size >= MAX_AUTH_TRACKED_IPS) {
                    val it = authFailures.entries.iterator()
                    if (!it.hasNext()) break
                    it.next()
                    it.remove()
                }
                authFailures[clientIp] = it
            }
            if (state.failures > 0 && (now - state.lastFailureNanos) > AUTH_DECAY_NS) {
                state.failures = 0
                state.hasLockout = false
                state.lockedUntilNanos = 0L
            }
            state.failures++
            state.lastFailureNanos = now
            if (state.failures > AUTH_FREE_FAILURES) {
                val shift = (state.failures - AUTH_FREE_FAILURES - 1).coerceAtMost(9)
                val lockMs = minOf(1_000L shl shift, AUTH_MAX_LOCKOUT_MS)
                state.lockedUntilNanos = now + lockMs * 1_000_000L
                state.hasLockout = true
            }
        }
    }

    private fun recordAuthSuccess(clientIp: String) {
        synchronized(authRateLock) {
            authFailures.remove(clientIp)
        }
    }

    private fun pruneGlobalFailuresLocked(now: Long) {
        while (globalFailWindow.isNotEmpty() && (now - globalFailWindow.first()) > GLOBAL_PBKDF2_WINDOW_NS) {
            globalFailWindow.removeFirst()
        }
    }

    private fun login(body: ByteArray, clientIp: String): Response {
        val token = try {
            JSONObject(String(body, Charsets.UTF_8)).optString("token", "")
        } catch (_: Throwable) {
            return error(400, "请求内容不是合法 JSON")
        }
        if (token.isNotEmpty() && hasSession(token)) {
            return loginResponse(token)
        }
        val waitSec = checkAuthRateLimit(clientIp)
        if (waitSec > 0L) {
            return error(429, "登录失败次数过多，请 ${waitSec} 秒后再试")
        }
        val epochBefore = synchronized(sessionsLock) { sessionEpoch }
        val initBefore = Config.webuiPasswordInitialized
        if (token.isNotEmpty() && Config.verifyWebuiPassword(token)) {
            val session = newSessionIfEpoch(epochBefore, initBefore)
                ?: return Response(401, JSONObject().put("ok", false).put("message", "密码已变更，请使用新密码重新登录"))
            recordAuthSuccess(clientIp)
            return loginResponse(session, !initBefore)
        }
        recordAuthFailure(clientIp)
        return Response(401, JSONObject().put("ok", false).put("message", "密码不正确或会话已失效"))
    }

    private fun loginResponse(
        session: String,
        mustChangePassword: Boolean = !Config.webuiPasswordInitialized
    ): Response =
        Response(
            200, JSONObject()
                .put("ok", true)
                .put("token", session)
                .put("mustChangePassword", mustChangePassword)
        )

    private fun newSession(): String {
        val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val session = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val now = System.nanoTime()
        val evictedStreams = ArrayList<WsConn>()
        synchronized(sessionsLock) {
            insertSessionLocked(session, now, evictedStreams)
        }
        for (c in evictedStreams) c.close()
        return session
    }

    /** 仅当会话代际与初始化状态均未变更时才签发新会话,彻底消除登录与改密/初始化的 TOCTOU 会话固定。 */
    private fun newSessionIfEpoch(expectedEpoch: Long, expectedInitialized: Boolean): String? {
        val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val session = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val now = System.nanoTime()
        val evictedStreams = ArrayList<WsConn>()
        val minted = synchronized(sessionsLock) {
            if (sessionEpoch != expectedEpoch || Config.webuiPasswordInitialized != expectedInitialized) {
                false
            } else {
                insertSessionLocked(session, now, evictedStreams)
                true
            }
        }
        for (c in evictedStreams) c.close()
        return if (minted) session else null
    }

    private fun insertSessionLocked(session: String, now: Long, evictedStreams: MutableList<WsConn>) {
        pruneExpiredSessionsLocked(now, evictedStreams)
        while (sessions.size >= SESSION_CAP) {
            val oldest = sessions.keys.iterator()
            if (!oldest.hasNext()) break
            val evictedToken = oldest.next()
            oldest.remove()
            collectStreamsForSessionLocked(evictedToken, evictedStreams)
        }
        sessions[session] = SessionEntry(now, now)
    }

    private fun hasSession(token: String): Boolean = lookupSessionEpoch(token) != null

    private fun lookupSessionEpoch(token: String): Long? {
        val now = System.nanoTime()
        val expiredStreams = ArrayList<WsConn>()
        val epoch = synchronized(sessionsLock) {
            val entry = sessions[token] ?: return null
            if (isExpired(entry, now)) {
                sessions.remove(token)
                collectStreamsForSessionLocked(token, expiredStreams)
                null
            } else {
                entry.lastSeenAtNanos = now
                sessionEpoch
            }
        }
        for (c in expiredStreams) c.close()
        return epoch
    }

    private fun isExpired(entry: SessionEntry, now: Long): Boolean =
        ((now - entry.lastSeenAtNanos) > SESSION_IDLE_TTL_NS) || ((now - entry.createdAtNanos) > SESSION_ABS_TTL_NS)

    private fun pruneExpiredSessionsLocked(now: Long, toClose: MutableList<WsConn>) {
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (isExpired(e.value, now)) {
                it.remove()
                collectStreamsForSessionLocked(e.key, toClose)
            }
        }
    }

    private fun collectStreamsForSessionLocked(token: String, out: MutableList<WsConn>) {
        val it = logStreams.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.sessionToken == token) {
                it.remove()
                out.add(entry.conn)
            }
        }
    }

    private fun dropSession(token: String) {
        if (token.isEmpty()) return
        val toClose = ArrayList<WsConn>()
        synchronized(sessionsLock) {
            sessions.remove(token)
            collectStreamsForSessionLocked(token, toClose)
        }
        for (c in toClose) c.close()
    }

    private fun clearSessions() {
        val toClose: List<WsConn>
        synchronized(sessionsLock) {
            sessionEpoch++
            sessions.clear()
            toClose = logStreams.map { it.conn }
            logStreams.clear()
        }
        for (c in toClose) c.close()
    }

    internal fun logStreamCount(): Int = synchronized(sessionsLock) {
        logStreams.removeAll { it.conn.closed }
        logStreams.size
    }

    internal fun registerLogStream(conn: WsConn, sessionToken: String?, expectedEpoch: Long): Boolean {
        val now = System.nanoTime()
        val expiredStreams = ArrayList<WsConn>()
        val registered = synchronized(sessionsLock) {
            logStreams.removeAll { it.conn.closed }
            if (!Config.webuiPasswordInitialized) return@synchronized false
            if (sessionEpoch != expectedEpoch) return@synchronized false
            if (sessionToken != null) {
                val entry = sessions[sessionToken]
                if (entry == null) return@synchronized false
                if (isExpired(entry, now)) {
                    sessions.remove(sessionToken)
                    collectStreamsForSessionLocked(sessionToken, expiredStreams)
                    return@synchronized false
                }
            }
            if (logStreams.size >= MAX_LOG_STREAMS) return@synchronized false
            logStreams.add(LogStreamEntry(conn, sessionToken))
            true
        }
        for (c in expiredStreams) c.close()
        return registered
    }

    internal fun unregisterLogStream(conn: WsConn) {
        synchronized(sessionsLock) {
            logStreams.removeAll { it.conn === conn || it.conn.closed }
        }
    }

    internal fun closeAllLogStreams() {
        val toClose: List<WsConn>
        synchronized(sessionsLock) {
            toClose = logStreams.map { it.conn }
            logStreams.clear()
        }
        for (c in toClose) c.close()
    }

    private fun logout(headers: Map<String, String>, query: Map<String, String>): Response {
        val credential = extractBearer(headers["authorization"])
        dropSession(credential)
        query["token"]?.let { dropSession(it) }
        return Response(200, JSONObject().put("ok", true))
    }

    private fun status(): JSONObject {
        val online = KernelGate.state == KernelGate.State.ONLINE
        val uin = KernelGate.selfUin()
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        return JSONObject()
            .put("online", online)
            .put("selfId", if (uin > 0) uin.toString() else "")
            .put("nickname", if (uin > 0) selfNick() else "")
            .put("avatar", if (uin > 0) "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=640" else "")
            .put("uptime", (System.currentTimeMillis() - PicoBoot.startedAt) / 1000)
            .put("version", PicoBoot.VERSION + if (PicoBoot.GIT_SHA.isNotEmpty() && PicoBoot.GIT_SHA != "unknown") "+" + PicoBoot.GIT_SHA else "")
            .put("qqVersion", hostQqVersion())
            .put("platform", "Android Watch")
            .put("canRestartQq", ContainerRestart.available())
            .put("protocol", "OneBot 11")
            .put("msgSent", Counters.sent())
            .put("msgRecv", Counters.recv())
            .put("lastRecvAt", Counters.lastRecvAt())
            .put("silenceMs", MsgWatchdog.silenceMs())
            .put("readReportsSent", Counters.readReportsSent())
            .put("readReportsFailed", Counters.readReportsFailed())
            .put("syncTriggered", Counters.syncTriggered())
            .put("lastSyncAt", Counters.lastSyncAt())
            .put("memoryMb", usedMb)
            .put("cpu", 0)
            .put("friends", ContactStore.friendCount())
            .put("groups", ContactStore.groupCount())
            .put("connections", Transport.count())
            .put("lastHeartbeat", MetaEvents.lastHeartbeatAt)
            .put("webuiUrl", NetUtil.webuiUrl())
            .put("lanIp", NetUtil.lanIp() ?: "")
    }

    private fun selfNick(): String = try {
        val uid = KernelGate.selfUid()
        if (uid.isEmpty()) "" else
            KernelGate.profileService()?.getCoreAndBaseInfo("pico", arrayListOf(uid))?.get(uid)?.coreInfo?.nick ?: ""
    } catch (_: Throwable) {
        ""
    }

    private fun hostQqVersion(): String = try {
        val ctx = mqq.app.MobileQQ.sMobileQQ
        if (ctx != null) ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" else ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * ConnectionInfo[]:配置里的每个连接项 + 它此刻**真实**的运行状态。
     */
    private fun connections(): JSONArray {
        val out = JSONArray()
        fun add(
            id: String, kind: String, name: String, peer: String,
            enabled: Boolean, connected: Boolean, peers: Int, since: Long, detail: String
        ) {
            out.put(
                JSONObject().put("id", id).put("kind", kind).put("name", name).put("peer", peer)
                    .put("enabled", enabled).put("connected", connected).put("peers", peers)
                    .put("since", since).put("detail", detail)
            )
        }

        fun addListener(o: JSONObject, kind: String, fallbackName: String, peer: String) {
            val id = o.optString("id")
            val enabled = o.optBoolean("enabled", true)
            val listening = enabled && PicoBoot.listening(id)
            val peers = if (listening) Transport.peers(id) else 0
            val detail = when {
                !enabled -> "已停用"
                !listening -> "监听未启动" + (PicoBoot.bindError(id)?.let { "：$it" } ?: "")
                peers > 0 -> "监听中 · $peers 个客户端在线"
                else -> "监听中 · 暂无客户端"
            }
            add(id, kind, o.optString("name", fallbackName), peer, enabled, listening, peers,
                if (listening) PicoBoot.networkAppliedAt else 0L, detail)
        }

        fun formatHost(raw: String): String {
            val h = raw.trim()
            return if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
        }

        val ws = Config.wsServers()
        for (i in 0 until ws.length()) {
            val o = ws.optJSONObject(i) ?: continue
            addListener(o, "ws-server", "正向 WS", "ws://" + formatHost(o.optString("host")) + ":" + o.optInt("port"))
        }
        val hs = Config.httpServers()
        for (i in 0 until hs.length()) {
            val o = hs.optJSONObject(i) ?: continue
            addListener(o, "http-server", "HTTP 服务", "http://" + formatHost(o.optString("host")) + ":" + o.optInt("port"))
        }
        val wc = Config.wsClients()
        for (i in 0 until wc.length()) {
            val o = wc.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val enabled = o.optBoolean("enabled", true)
            val connected = enabled && PicoBoot.reverseConnected(id)
            val detail = when {
                !enabled -> "已停用"
                connected -> "已连接"
                PicoBoot.reverseRunning(id) -> "重连中…"
                else -> "未启动"
            }
            add(id, "ws-client", o.optString("name", "反向 WS"), o.optString("url"),
                enabled, connected, if (connected) 1 else 0,
                if (connected) PicoBoot.reverseSince(id) else 0L, detail)
        }
        val hc = Config.httpClients()
        for (i in 0 until hc.length()) {
            val o = hc.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val enabled = o.optBoolean("enabled", true)
            val url = o.optString("url")
            val reporting = enabled && HttpReporter.reporting(url)
            add(id, "http-client", o.optString("name", "HTTP 上报"), url,
                enabled, reporting, 0, if (reporting) PicoBoot.networkAppliedAt else 0L,
                if (!enabled) "已停用" else if (reporting) "已生效 · 有事件即上报" else "未生效")
        }
        return out
    }

    private fun putConfig(body: ByteArray): Response = synchronized(fileWriteLock) {
        val patch = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: JSONException) {
            return error(400, "请求内容不是合法 JSON")
        }
        patch.optJSONObject("webui")?.apply {
            remove("token")
            remove("tokenIsDefault")
            remove("passwordSalt")
            remove("passwordHash")
            remove("passwordInitialized")
        }
        val errors = validateConfigPatch(patch, validateNetworkFallback = true)
        if (errors.isNotEmpty()) {
            return Response(
                400,
                JSONObject().put("ok", false).put("message", errors.joinToString("；"))
            )
        }
        val previous = Config.raw().toString()
        try {
            Config.applyAppConfig(patch)
        } catch (err: IllegalArgumentException) {
            return error(400, err.message ?: "配置无效")
        }
        PicoLog.i("config updated via webui")
        return Response(200, applyRuntimeConfig(previous).put("ok", true))
    }

    /**
     * 保存后真正去动运行时,并把**实际发生了什么**原样回给前端。
     */
    private fun applyRuntimeConfig(previousText: String): JSONObject {
        val previous = JSONObject(previousText)
        val networkChanged = previous.optJSONObject("network")?.toString() != Config.network().toString()
        val reload = if (networkChanged) PicoBoot.reloadNetwork() else null
        val oldWebui = previous.optJSONObject("webui") ?: JSONObject()
        val restart = oldWebui.optString("host", "0.0.0.0") != Config.webuiHost ||
            oldWebui.optInt("port", 6099) != Config.webuiPort ||
            previous.optBoolean("enable", true) != Config.enabled ||
            (previous.optJSONObject("general")?.optInt("event_queue_size", 8192) ?: 8192) != Config.eventQueueSize
        val applied = JSONObject()
            .put("reloaded", reload != null)
            .put("listeners", reload?.listeners ?: 0)
            .put("reverse", reload?.reverse ?: 0)
            .put("reporters", reload?.reporters ?: 0)
            .put("appliedAt", if (reload != null) PicoBoot.networkAppliedAt else 0L)
        return JSONObject().put("restartRequired", restart)
            .put("warnings", JSONArray(reload?.errors ?: emptyList<String>()))
            .put("applied", applied)
    }

    private fun logs(query: Map<String, String>): JSONArray {
        val limit = (query["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 4096)
        val arr = JSONArray()
        for (e in PicoLog.entries(limit)) arr.put(e.toJson())
        return arr
    }

    private fun listFiles(query: Map<String, String>): Response {
        val dir = resolveManagedPath(query["path"] ?: "")
            ?: return error(400, "目录路径无效")
        if (isBlockedManagedPath(dir)) return error(403, "不允许访问受保护目录")
        if (!dir.exists()) return error(404, "目录不存在")
        if (!dir.isDirectory) return error(400, "目标不是目录")

        val entries = JSONArray()
        val children = dir.listFiles()?.sortedWith(
            compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
        ) ?: emptyList()
        for (child in children) {
            if (isBlockedManagedPath(child)) continue
            val relative = relativePath(child) ?: continue
            entries.put(
                JSONObject()
                    .put("name", child.name)
                    .put("path", relative)
                    .put("type", if (child.isDirectory) "directory" else "file")
                    .put("size", if (child.isFile) child.length() else 0)
                    .put("modifiedAt", child.lastModified())
                    .put("editable", child.isFile && isEditableText(child))
            )
        }
        val current = relativePath(dir) ?: ""
        val parent = if (current.isEmpty()) JSONObject.NULL else current.substringBeforeLast('/', "")
        val activeConfig = Config.activeConfigFile?.takeIf { it.isFile && !isBlockedManagedPath(it) }?.let { config ->
            relativePath(config)?.let { relative ->
                JSONObject()
                    .put("name", config.name)
                    .put("path", relative)
                    .put("type", "file")
                    .put("size", config.length())
                    .put("modifiedAt", config.lastModified())
                    .put("editable", isEditableText(config))
            }
        }
        return Response(
            200,
            JSONObject()
                .put("root", Config.fileManagerDir.absolutePath)
                .put("path", current)
                .put("parent", parent)
                .put("activeConfig", activeConfig ?: JSONObject.NULL)
                .put("entries", entries)
        )
    }

    private fun readFile(query: Map<String, String>): Response = synchronized(fileWriteLock) {
        val target = resolveManagedPath(query["path"] ?: "")
            ?: return error(400, "文件路径无效")
        if (isBlockedManagedPath(target)) return error(403, "不允许通过文件管理器读取该文件")
        if (!target.isFile) return error(404, "文件不存在")
        if (!isEditableText(target)) {
            return error(415, "仅支持编辑不超过 1 MiB 的 UTF-8 文本文件")
        }
        val rawContent = readBoundedUtf8(target, MAX_EDIT_BYTES)
            ?: return error(415, "仅支持编辑不超过 1 MiB 的 UTF-8 文本文件")
        val content = if (isActiveConfigFile(target)) {
            redactConfigAuthFields(rawContent)
        } else {
            rawContent
        }
        return Response(
            200,
            JSONObject()
                .put("path", relativePath(target))
                .put("name", target.name)
                .put("content", content)
                .put("size", content.toByteArray(Charsets.UTF_8).size)
                .put("modifiedAt", target.lastModified())
        )
    }

    private fun putFile(body: ByteArray): Response {
        val req = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Throwable) {
            return error(400, "请求内容不是合法 JSON")
        }
        val rawPath = req.optString("path", "")
        if (!req.has("content") || req.isNull("content")) return error(400, "缺少文件内容")
        val content = req.opt("content") as? String ?: return error(400, "文件内容必须为字符串")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_EDIT_BYTES) return error(413, "文件内容不能超过 1 MiB")
        val expectedModifiedAt = req.optLong("expectedModifiedAt", -1L)

        var runtime = JSONObject().put("restartRequired", false).put("warnings", JSONArray())
            .put("applied", JSONObject().put("reloaded", false).put("listeners", 0)
                .put("reverse", 0).put("reporters", 0).put("appliedAt", 0L))
        val savedSize: Long
        val savedModifiedAt: Long
        val savedRelPath: String

        synchronized(fileWriteLock) {
            val target = resolveManagedPath(rawPath)
                ?: return error(400, "文件路径无效")
            if (isBlockedManagedPath(target)) {
                return error(403, "不允许通过文件管理器修改受保护文件或 webui 静态资源")
            }
            if (!target.isFile) return error(404, "文件不存在")
            if (!isEditableText(target)) {
                return error(415, "仅支持编辑不超过 1 MiB 的 UTF-8 文本文件")
            }
            val activeConfig = isActiveConfigFile(target)
            if (target.extension.equals("json", ignoreCase = true)) {
                try {
                    if (activeConfig) JSONObject(content) else org.json.JSONTokener(content).nextValue()
                } catch (t: Throwable) {
                    return error(400, "JSON 格式错误: " + t.message)
                }
            }
            if (expectedModifiedAt >= 0 && target.lastModified() != expectedModifiedAt) {
                return error(409, "文件已被其他程序修改，请重新加载后再保存")
            }
            try {
                if (activeConfig) {
                    val candidate = JSONObject(content)
                    val errors = validateConfigPatch(candidate, validateNetworkFallback = false)
                    if (errors.isNotEmpty()) {
                        return error(400, "配置无效: " + errors.joinToString("；"))
                    }
                    // 保留既有密码认证状态,禁止通过文件编辑绕过改密或降级密码策略
                    preserveConfigAuthFields(candidate)
                    val previous = Config.raw().toString()
                    Config.replaceConfig(candidate)
                    runtime = applyRuntimeConfig(previous)
                } else {
                    writeFileAtomically(target, bytes)
                }
            } catch (err: IllegalArgumentException) {
                return error(400, "配置无效: " + err.message)
            } catch (t: Throwable) {
                PicoLog.e("webui putFile failed for $rawPath", t)
                return error(500, "文件保存失败，请检查存储空间或权限")
            }
            savedSize = target.length()
            savedModifiedAt = target.lastModified()
            savedRelPath = relativePath(target) ?: target.name
        }

        PicoLog.i("file updated via webui: $savedRelPath")
        return Response(
            200,
            JSONObject()
                .put("ok", true)
                .put("size", savedSize)
                .put("modifiedAt", savedModifiedAt)
                .put("restartRequired", runtime.optBoolean("restartRequired"))
                .put("warnings", runtime.getJSONArray("warnings"))
                .put("applied", runtime.getJSONObject("applied"))
        )
    }

    private fun isActiveConfigFile(target: File): Boolean = try {
        Config.activeConfigFile?.canonicalFile == target.canonicalFile
    } catch (_: Throwable) {
        false
    }

    private fun redactConfigAuthFields(rawContent: String): String {
        return try {
            val obj = JSONObject(rawContent)
            obj.optJSONObject("webui")?.apply {
                remove("token")
                remove("tokenIsDefault")
                remove("passwordSalt")
                remove("passwordHash")
                put("passwordInitialized", Config.webuiPasswordInitialized)
            }
            obj.toString(2)
        } catch (_: Throwable) {
            rawContent.replace(
                Regex("\"(passwordSalt|passwordHash|token)\"\\s*:\\s*\"[^\"]*\""),
                "\"$1\": \"***\""
            )
        }
    }

    private fun preserveConfigAuthFields(candidate: JSONObject) {
        val curWebui = Config.webui()
        val nextWebui = candidate.optJSONObject("webui") ?: JSONObject().also { candidate.put("webui", it) }
        nextWebui.remove("token")
        nextWebui.remove("tokenIsDefault")
        if (Config.webuiPasswordInitialized) {
            nextWebui.put("passwordInitialized", true)
            nextWebui.put("passwordSalt", curWebui.optString("passwordSalt", ""))
            nextWebui.put("passwordHash", curWebui.optString("passwordHash", ""))
        } else {
            nextWebui.put("passwordInitialized", false)
            nextWebui.remove("passwordSalt")
            nextWebui.remove("passwordHash")
        }
    }

    private fun writeFileAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw java.io.IOException("invalid target parent")
        parent.mkdirs()
        val tmp = File.createTempFile(".pico-edit-", ".tmp", parent)
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                }
            }
            if (!tmp.renameTo(target)) {
                throw java.io.IOException("无法原子替换目标文件")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 屏蔽 webui 静态资源目录及含明文/哈希的备份与临时配置文件。 */
    private fun isBlockedManagedPath(file: File): Boolean {
        val target = try { file.canonicalFile } catch (_: Throwable) { return true }
        val protectedDirs = listOfNotNull(
            try { File(Config.dataDir, "webui").canonicalFile } catch (_: Throwable) { null },
            try { File(Config.fileManagerDir, "webui").canonicalFile } catch (_: Throwable) { null },
            try { Config.webuiDir.canonicalFile } catch (_: Throwable) { null }
        )
        for (dir in protectedDirs) {
            if (target.path == dir.path || target.path.startsWith(dir.path + File.separator)) {
                return true
            }
        }
        val name = target.name.lowercase(Locale.ROOT)
        if (name == "config.v1.json" || name.endsWith(".tmp") || name.startsWith("config.json.corrupt.")) {
            return true
        }
        return false
    }

    private fun resolveManagedPath(relative: String): File? {
        if (relative.indexOf('\u0000') >= 0) return null
        val normalized = relative.replace('\\', '/').trimStart('/')
        val root = try { Config.fileManagerDir.canonicalFile } catch (_: Throwable) { return null }
        val target = try { File(root, normalized).canonicalFile } catch (_: Throwable) { return null }
        return if (target.path == root.path || target.path.startsWith(root.path + File.separator)) target else null
    }

    private fun relativePath(file: File): String? = try {
        val root = Config.fileManagerDir.canonicalFile
        val target = file.canonicalFile
        if (target.path == root.path) ""
        else if (target.path.startsWith(root.path + File.separator)) {
            target.path.substring(root.path.length + 1).replace(File.separatorChar, '/')
        } else null
    } catch (_: Throwable) {
        null
    }

    private fun isEditableText(file: File): Boolean {
        if (!file.isFile) return false
        val len = file.length()
        if (len > MAX_EDIT_BYTES) return false
        return try {
            val probeLen = minOf(8192L, len).toInt()
            val probe = file.inputStream().use { input ->
                val buffer = ByteArray(probeLen)
                var off = 0
                while (off < probeLen) {
                    val n = input.read(buffer, off, probeLen - off)
                    if (n <= 0) break
                    off += n
                }
                if (off <= 0) ByteArray(0) else buffer.copyOf(off)
            }
            if (probe.any { it == 0.toByte() }) return false
            val checkLen = if (len > probe.size) trimIncompleteUtf8Tail(probe) else probe.size
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(probe, 0, checkLen))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 截断探测缓冲末尾被 8 KiB 边界劈开的不完整 UTF-8 多字节序列(最多 3 字节)。 */
    private fun trimIncompleteUtf8Tail(buf: ByteArray): Int {
        val n = buf.size
        if (n == 0) return 0
        var i = n - 1
        var cont = 0
        while (i >= 0 && cont < 3 && (buf[i].toInt() and 0xC0) == 0x80) {
            i--
            cont++
        }
        if (i < 0) return n
        val b = buf[i].toInt() and 0xFF
        val expected = when {
            (b and 0x80) == 0 -> 1
            (b and 0xE0) == 0xC0 -> 2
            (b and 0xF0) == 0xE0 -> 3
            (b and 0xF8) == 0xF0 -> 4
            else -> return n
        }
        return if (n - i < expected) i else n
    }

    private fun readBoundedUtf8(file: File, maxBytes: Long): String? {
        return try {
            val out = ByteArrayOutputStream()
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
            }
            val bytes = out.toByteArray()
            if (bytes.any { it == 0.toByte() }) return null
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun error(status: Int, message: String): Response =
        Response(status, JSONObject().put("ok", false).put("message", message))

    private fun restart(): Response {
        if (!ContainerRestart.available()) return error(403, "仅支持容器内重启 QQ；请确认已更新容器镜像且守护服务正在运行")
        return try {
            if (!ContainerRestart.request()) return error(429, "QQ 重启请求正在处理，请稍后再试")
            PicoLog.i("QQ restart requested via authenticated WebUI")
            Response(202, JSONObject().put("ok", true)
                .put("wording", "QQ 将在数秒后重启，容器不会重启；恢复后请重新登录 WebUI"))
        } catch (err: Throwable) {
            PicoLog.e("QQ restart request failed", err)
            error(503, "无法提交重启请求，请检查容器守护服务")
        }
    }

    private fun accountLogout(): JSONObject {
        val r = ActionRouter.call("_pico_logout", JSONObject())
        return JSONObject().put("ok", r.optString("status") == "ok")
    }

    private fun setToken(body: ByteArray, clientIp: String): Response {
        val req = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Throwable) {
            return Response(400, JSONObject().put("ok", false).put("message", "请求内容不是合法 JSON"))
        }
        val rawToken = req.optString("token", "")
        if (rawToken != rawToken.trim()) {
            return Response(400, JSONObject().put("ok", false).put("message", "控制台密码首尾不能包含空白字符"))
        }
        val token = rawToken
        if (token.length < 8) {
            return Response(400, JSONObject().put("ok", false).put("message", "控制台密码至少 8 位"))
        }
        if (token == Config.DEFAULT_WEBUI_PASSWORD) {
            return Response(400, JSONObject().put("ok", false).put("message", "不能继续使用初始密码"))
        }
        if (Config.webuiPasswordInitialized) {
            val waitSec = checkAuthRateLimit(clientIp)
            if (waitSec > 0L) {
                return error(429, "密码校验失败次数过多，请 ${waitSec} 秒后再试")
            }
            val current = req.optString("current", "")
            if (current.isEmpty()) {
                return Response(400, JSONObject().put("ok", false).put("message", "修改密码需要提供当前密码"))
            }
            if (!Config.changeWebuiPassword(current, token)) {
                recordAuthFailure(clientIp)
                return Response(403, JSONObject().put("ok", false).put("message", "当前密码不正确"))
            }
            recordAuthSuccess(clientIp)
            // 改密后让所有已签发的会话与日志流失效,旧浏览器需重新登录
            clearSessions()
            PicoLog.i("webui password changed")
            return Response(200, JSONObject().put("ok", true).put("sessionsRevoked", true))
        }
        if (!Config.initializeWebuiPassword(token)) {
            return if (Config.webuiPasswordInitialized) {
                Response(409, JSONObject().put("ok", false).put("message", "控制台密码已设置，不允许再次修改"))
            } else {
                error(500, "保存控制台密码失败，请检查存储空间或权限")
            }
        }
        // 首次初始化密码必须撤销所有预初始化会话并轮换签发新会话令牌,封堵会话固定
        clearSessions()
        val newToken = newSession()
        PicoLog.i("webui password initialized; pre-init sessions revoked")
        return Response(
            200,
            JSONObject()
                .put("ok", true)
                .put("token", newToken)
                .put("sessionsRevoked", true)
        )
    }

    /** 二维码 TTL(秒),与 PicoLoginManager.info() 的过期判定一致。 */
    private const val QR_TTL_SEC = 85
    private const val QR_REFRESH_WAIT_NS = 3_000_000_000L

    /**
     * `GET /api/qr` 只读不触发刷新(前端 2s 轮询,不能每次都向登录服务器要新码);
     * `POST /api/qr/refresh` 才主动要一张(在 LoginWithStateFragment 上则先点"切换账号"),最多等 3s。
     */
    private fun qr(refresh: Boolean): JSONObject {
        val out = JSONObject()
        val hasFrag = PicoLoginManager.qrFragmentRef?.get() != null
        val hasState = PicoLoginManager.hasLoginEntry
        if (KernelGate.state == KernelGate.State.ONLINE) {
            return out.put("status", "online").put("online", true).put("user_id", KernelGate.selfUin())
                .put("url", "").put("image", "").put("ageSeconds", 0).put("expiresIn", 0)
                .put("scanned", false).put("hasFragment", hasFrag).put("hasStateFragment", hasState)
        }
        if (refresh) {
            val before = PicoLoginManager.currentQrSnapshot()
            if (PicoLoginManager.refreshQr()) {
                val deadline = System.nanoTime() + QR_REFRESH_WAIT_NS
                while ((deadline - System.nanoTime()) > 0L) {
                    val cur = PicoLoginManager.currentQrSnapshot()
                    if (cur.generation > before.generation && !cur.expired && !cur.url.isNullOrBlank()) {
                        break
                    }
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        val snap = PicoLoginManager.currentQrSnapshot()
        val url = snap.url ?: ""
        val bytes = snap.bytes
        val age = if (snap.receivedAtWallMs <= 0L) {
            -1
        } else {
            ((System.nanoTime() - snap.receivedAtNs).coerceAtLeast(0L) / 1_000_000_000L).toInt()
        }
        val expired = snap.expired || (age > QR_TTL_SEC)
        val status = when {
            url.isEmpty() -> "waiting_qr"
            expired -> "expired"
            else -> "ok"
        }
        val image = if (status == "ok" && bytes != null)
            "data:" + imageMime(bytes) + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        else ""
        return out.put("status", status).put("online", false)
            .put("url", url).put("image", image)
            .put("ageSeconds", if (age < 0) 0 else age)
            .put("expiresIn", if (status == "ok") (QR_TTL_SEC - age).coerceAtLeast(0) else 0)
            .put("scanned", snap.scanned)
            .put("hasFragment", PicoLoginManager.qrFragmentRef?.get() != null)
            .put("hasStateFragment", PicoLoginManager.hasLoginEntry)
    }

    private fun imageMime(b: ByteArray): String = when {
        b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "image/gif"
        b.size > 12 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "image/webp"
        else -> "image/png"
    }
}
