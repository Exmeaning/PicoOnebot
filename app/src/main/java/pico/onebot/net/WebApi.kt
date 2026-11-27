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
import java.util.concurrent.Semaphore

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
    private const val PRE_INIT_SESSION_TTL_NS = 10L * 60L * 1_000_000_000L
    const val MAX_LOG_STREAMS = 16

    // 鉴权失败限流与指数退避参数(单调时钟)
    private const val MAX_AUTH_TRACKED_IPS = 256
    private const val AUTH_FREE_FAILURES = 4
    private const val AUTH_DECAY_NS = 15L * 60L * 1_000_000_000L
    private const val AUTH_MAX_LOCKOUT_MS = 300_000L
    private const val GLOBAL_PBKDF2_WINDOW_NS = 60L * 1_000_000_000L
    private const val GLOBAL_PBKDF2_MAX_FAILS = 100
    private const val MAX_CONCURRENT_PBKDF2 = 3

    private data class SessionEntry(
        val createdAtNanos: Long,
        var lastSeenAtNanos: Long,
        val preInitOnly: Boolean = false
    )
    private data class SessionLookup(val epoch: Long, val preInitOnly: Boolean)
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
        val authEpoch: Long = 0L,
        val preInitOnly: Boolean = false,
        val rateLimitMessage: String? = null
    )

    internal data class PreAuthCheck(
        val status: Int,
        val message: String? = null,
        val mustChangePassword: Boolean = false,
        val isAuthenticatedSession: Boolean = false,
        val expectedEpoch: Long? = null
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
    private val pbkdf2Semaphore = Semaphore(MAX_CONCURRENT_PBKDF2)

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
            errors.addAll(Config.validateWebui(w))
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

    internal fun isAllowedForPreInit(method: String, path: String): Boolean =
        (path == "/api/status" && method == "GET") ||
            (path == "/api/logout" && method == "POST") ||
            (path == "/api/webui/token" && method == "PUT")

    /**
     * 读请求体前的廉价 O(1) 预检(不执行 PBKDF2,不免除读完 body 后的完整鉴权):
     * - 无 Authorization 头立刻拒 401;
     * - 单 IP 已锁定立刻拒 429;
     * - 命中会话表时校验预初始化/恢复模式路由门禁并记录预期 sessionEpoch;
     * - 未命中会话表时(可能是运维脚本直传密码或伪造令牌)交由调用方走未鉴权并发限流读体,再由 [handle] 完整校验。
     */
    internal fun preCheckRequest(
        method: String,
        path: String,
        headers: Map<String, String>,
        clientIp: String
    ): PreAuthCheck {
        val headerCred = extractBearer(headers["authorization"])
        if (headerCred.isEmpty()) {
            return PreAuthCheck(status = 401, message = "unauthorized")
        }
        val ipWaitSec = checkIpRateLimit(clientIp)
        if (ipWaitSec > 0L) {
            return PreAuthCheck(status = 429, message = "认证尝试过于频繁，请 ${ipWaitSec} 秒后再试")
        }
        val session = lookupSession(headerCred, touch = false)
        if (session != null) {
            if (Config.corruptRecoveryMode &&
                ((path == "/api/config" && method == "PUT") || (path == "/api/webui/token" && method == "PUT"))
            ) {
                return PreAuthCheck(
                    status = 409,
                    message = "当前处于损坏配置恢复模式，拒绝覆盖 config.json；请先在文件管理器中修复并保存 config.json"
                )
            }
            if (!Config.corruptRecoveryMode &&
                (session.preInitOnly || !Config.webuiPasswordInitialized) &&
                !isAllowedForPreInit(method, path)
            ) {
                return PreAuthCheck(
                    status = 428,
                    mustChangePassword = true,
                    message = "首次使用必须先修改控制台密码"
                )
            }
            return PreAuthCheck(
                status = 200,
                isAuthenticatedSession = true,
                expectedEpoch = session.epoch
            )
        }
        return PreAuthCheck(status = 200, isAuthenticatedSession = false, expectedEpoch = null)
    }

    fun handle(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray,
        clientIp: String = "unknown",
        expectedSessionEpoch: Long? = null
    ): Response {
        // 登录不需要先有票据
        if (path == "/api/auth/login" && method == "POST") return login(body, clientIp)

        // 读取完整请求体后始终重新校验 Session / sessionEpoch / mustChangePassword / IP 锁定,绝不使用 preAuthorized 旁路。
        val auth = checkAuthorization(
            headers,
            query,
            clientIp,
            allowQuerySession = false,
            expectedSessionEpoch = expectedSessionEpoch
        )
        if (auth.rateLimited) {
            return error(429, auth.rateLimitMessage ?: "认证尝试过于频繁，请 ${auth.retryAfterSec} 秒后再试")
        }
        if (!auth.authorized) {
            return Response(401, JSONObject().put("ok", false).put("message", "unauthorized"))
        }
        if (!Config.corruptRecoveryMode &&
            (auth.preInitOnly || !Config.webuiPasswordInitialized) &&
            !isAllowedForPreInit(method, path)
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
        allowQuerySession: Boolean,
        expectedSessionEpoch: Long? = null
    ): AuthCheck {
        // 单 IP 触发锁定后连带阻断该 IP 的后续请求(含慢体期间触发锁定的写请求),但不因全局 PBKDF2 节流影响已登录会话
        val ipWaitSec = checkIpRateLimit(clientIp)
        if (ipWaitSec > 0L) {
            return AuthCheck(authorized = false, rateLimited = true, retryAfterSec = ipWaitSec)
        }

        val headerCred = extractBearer(headers["authorization"])
        val queryCred = if (allowQuerySession) (query["token"] ?: "") else ""

        // 1. 优先 O(1) 校验已签发的会话令牌(零 PBKDF2 开销)
        if (headerCred.isNotEmpty()) {
            val found = lookupSession(headerCred, touch = true, expectedEpoch = expectedSessionEpoch)
            if (found != null) {
                return AuthCheck(
                    authorized = true,
                    sessionToken = headerCred,
                    authEpoch = found.epoch,
                    preInitOnly = found.preInitOnly
                )
            }
        }
        if (queryCred.isNotEmpty()) {
            val found = lookupSession(queryCred, touch = true, expectedEpoch = expectedSessionEpoch)
            if (found != null) {
                return AuthCheck(
                    authorized = true,
                    sessionToken = queryCred,
                    authEpoch = found.epoch,
                    preInitOnly = found.preInitOnly
                )
            }
        }

        // 2. 仅允许 Authorization 头回退校验密码(兼容运维脚本),受限流与 PBKDF2 并发信号量保护;
        //    绝不对 ?token= 查询串运行 PBKDF2。
        if (headerCred.isNotEmpty()) {
            val waitSec = checkAuthRateLimit(clientIp)
            if (waitSec > 0L) {
                return AuthCheck(authorized = false, rateLimited = true, retryAfterSec = waitSec)
            }
            val epochBefore: Long
            val initBefore: Boolean
            val recoveryBefore: Boolean
            synchronized(sessionsLock) {
                epochBefore = sessionEpoch
                initBefore = Config.webuiPasswordInitialized
                recoveryBefore = Config.corruptRecoveryMode
            }
            if (expectedSessionEpoch != null && epochBefore != expectedSessionEpoch) {
                return AuthCheck(authorized = false)
            }
            val needsPbkdf2 = !recoveryBefore && initBefore
            if (needsPbkdf2 && !pbkdf2Semaphore.tryAcquire()) {
                return AuthCheck(
                    authorized = false,
                    rateLimited = true,
                    retryAfterSec = 2L,
                    rateLimitMessage = "认证服务繁忙，请稍后再试"
                )
            }
            val verified = try {
                Config.verifyWebuiPassword(headerCred)
            } finally {
                if (needsPbkdf2) pbkdf2Semaphore.release()
            }
            if (verified) {
                val stillValid = synchronized(sessionsLock) {
                    sessionEpoch == epochBefore &&
                        Config.webuiPasswordInitialized == initBefore &&
                        Config.corruptRecoveryMode == recoveryBefore
                }
                if (!stillValid) {
                    return AuthCheck(authorized = false)
                }
                recordAuthSuccess(clientIp)
                return AuthCheck(
                    authorized = true,
                    sessionToken = null,
                    authEpoch = epochBefore,
                    preInitOnly = !recoveryBefore && !initBefore
                )
            }
            recordAuthFailure(clientIp, countGlobal = needsPbkdf2)
        }
        return AuthCheck(authorized = false)
    }

    private fun extractBearer(authHeader: String?): String {
        val auth = authHeader ?: return ""
        return if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7) else auth
    }

    /** 仅检查单 IP 退避锁定(不影响全局兜底计数)。 */
    private fun checkIpRateLimit(clientIp: String): Long {
        val now = System.nanoTime()
        synchronized(authRateLock) {
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

    /** 单 IP 退避为主 + 全局 PBKDF2 兜底节流(仅针对密码验证)。 */
    private fun checkAuthRateLimit(clientIp: String): Long {
        val now = System.nanoTime()
        synchronized(authRateLock) {
            val state = authFailures[clientIp]
            if (state != null) {
                if ((now - state.lastFailureNanos) > AUTH_DECAY_NS) {
                    authFailures.remove(clientIp)
                } else if (state.hasLockout) {
                    val remNs = state.lockedUntilNanos - now
                    if (remNs > 0L) {
                        return ((remNs + 999_999_999L) / 1_000_000_000L).coerceAtLeast(1L)
                    }
                    state.hasLockout = false
                }
            }
            pruneGlobalFailuresLocked(now)
            if (globalFailWindow.size >= GLOBAL_PBKDF2_MAX_FAILS) {
                val oldest = globalFailWindow.first()
                val remNs = ((oldest + GLOBAL_PBKDF2_WINDOW_NS) - now).coerceAtLeast(1_000_000_000L)
                return (remNs + 999_999_999L) / 1_000_000_000L
            }
            return 0L
        }
    }

    private fun recordAuthFailure(clientIp: String, countGlobal: Boolean) {
        val now = System.nanoTime()
        synchronized(authRateLock) {
            if (countGlobal) {
                pruneGlobalFailuresLocked(now)
                globalFailWindow.addLast(now)
            }
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
        val req = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Throwable) {
            return error(400, "请求内容不是合法 JSON")
        }
        val ipWaitSec = checkIpRateLimit(clientIp)
        if (ipWaitSec > 0L) {
            return error(429, "登录失败次数过多，请 ${ipWaitSec} 秒后再试")
        }
        val rawToken = req.opt("token")
        val token = rawToken as? String ?: ""
        if (token.isEmpty()) {
            // 空值或畸形 token 仅计入单 IP 退避,绝不计入 globalFailWindow 防止全局 DoS 锁死
            recordAuthFailure(clientIp, countGlobal = false)
            return Response(401, JSONObject().put("ok", false).put("message", "密码不正确或会话已失效"))
        }
        val existing = lookupSession(token, touch = true)
        if (existing != null) {
            val recovery = Config.corruptRecoveryMode
            val mustChange = !recovery && (existing.preInitOnly || !Config.webuiPasswordInitialized)
            return loginResponse(token, mustChangePassword = mustChange, recoveryMode = recovery)
        }
        val waitSec = checkAuthRateLimit(clientIp)
        if (waitSec > 0L) {
            return error(429, "登录失败次数过多，请 ${waitSec} 秒后再试")
        }
        val epochBefore: Long
        val initBefore: Boolean
        val recoveryBefore: Boolean
        synchronized(sessionsLock) {
            epochBefore = sessionEpoch
            initBefore = Config.webuiPasswordInitialized
            recoveryBefore = Config.corruptRecoveryMode
        }
        val needsPbkdf2 = !recoveryBefore && initBefore
        if (needsPbkdf2 && !pbkdf2Semaphore.tryAcquire()) {
            return error(429, "认证服务繁忙，请稍后再试")
        }
        val verified = try {
            Config.verifyWebuiPassword(token)
        } finally {
            if (needsPbkdf2) pbkdf2Semaphore.release()
        }
        if (verified) {
            val preInit = !recoveryBefore && !initBefore
            val session = newSessionIfState(epochBefore, initBefore, recoveryBefore, preInitOnly = preInit)
                ?: return Response(401, JSONObject().put("ok", false).put("message", "密码已变更，请使用新密码重新登录"))
            recordAuthSuccess(clientIp)
            return loginResponse(session, mustChangePassword = preInit, recoveryMode = recoveryBefore)
        }
        recordAuthFailure(clientIp, countGlobal = needsPbkdf2)
        return Response(401, JSONObject().put("ok", false).put("message", "密码不正确或会话已失效"))
    }

    private fun loginResponse(
        session: String,
        mustChangePassword: Boolean = !Config.corruptRecoveryMode && !Config.webuiPasswordInitialized,
        recoveryMode: Boolean = Config.corruptRecoveryMode
    ): Response =
        Response(
            200, JSONObject()
                .put("ok", true)
                .put("token", session)
                .put("mustChangePassword", mustChangePassword)
                .put("recoveryMode", recoveryMode)
        )

    private fun generateSessionToken(): String {
        val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** 仅当会话代际、初始化状态与恢复模式均未变更时才签发新会话,彻底消除登录与改密/初始化的 TOCTOU 会话固定。 */
    private fun newSessionIfState(
        expectedEpoch: Long,
        expectedInitialized: Boolean,
        expectedRecoveryMode: Boolean,
        preInitOnly: Boolean
    ): String? {
        val session = generateSessionToken()
        val now = System.nanoTime()
        val evictedStreams = ArrayList<WsConn>()
        val minted = synchronized(sessionsLock) {
            if (sessionEpoch != expectedEpoch ||
                Config.webuiPasswordInitialized != expectedInitialized ||
                Config.corruptRecoveryMode != expectedRecoveryMode
            ) {
                false
            } else {
                insertSessionLocked(session, now, preInitOnly, evictedStreams)
                true
            }
        }
        for (c in evictedStreams) c.close()
        return if (minted) session else null
    }

    private fun insertSessionLocked(
        session: String,
        now: Long,
        preInitOnly: Boolean,
        evictedStreams: MutableList<WsConn>
    ) {
        pruneExpiredSessionsLocked(now, evictedStreams)
        while (sessions.size >= SESSION_CAP) {
            val oldest = sessions.keys.iterator()
            if (!oldest.hasNext()) break
            val evictedToken = oldest.next()
            oldest.remove()
            collectStreamsForSessionLocked(evictedToken, evictedStreams)
        }
        sessions[session] = SessionEntry(now, now, preInitOnly)
    }

    private fun lookupSession(
        token: String,
        touch: Boolean,
        expectedEpoch: Long? = null
    ): SessionLookup? {
        val now = System.nanoTime()
        val expiredStreams = ArrayList<WsConn>()
        val result = synchronized(sessionsLock) {
            if (expectedEpoch != null && sessionEpoch != expectedEpoch) return@synchronized null
            val entry = sessions[token] ?: return@synchronized null
            if (isExpired(entry, now) || (entry.preInitOnly && Config.webuiPasswordInitialized)) {
                sessions.remove(token)
                collectStreamsForSessionLocked(token, expiredStreams)
                null
            } else {
                if (touch) entry.lastSeenAtNanos = now
                SessionLookup(sessionEpoch, entry.preInitOnly)
            }
        }
        for (c in expiredStreams) c.close()
        return result
    }

    private fun isExpired(entry: SessionEntry, now: Long): Boolean {
        if (entry.preInitOnly) {
            return (now - entry.createdAtNanos) > PRE_INIT_SESSION_TTL_NS
        }
        return ((now - entry.lastSeenAtNanos) > SESSION_IDLE_TTL_NS) ||
            ((now - entry.createdAtNanos) > SESSION_ABS_TTL_NS)
    }

    private fun pruneExpiredSessionsLocked(now: Long, toClose: MutableList<WsConn>) {
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (isExpired(e.value, now) || (e.value.preInitOnly && Config.webuiPasswordInitialized)) {
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

    private fun revokeAllSessionsLocked(toClose: MutableList<WsConn>) {
        sessionEpoch++
        sessions.clear()
        for (entry in logStreams) {
            toClose.add(entry.conn)
        }
        logStreams.clear()
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
            if (!Config.webuiPasswordInitialized && !Config.corruptRecoveryMode) return@synchronized false
            if (sessionEpoch != expectedEpoch) return@synchronized false
            if (sessionToken != null) {
                val entry = sessions[sessionToken]
                if (entry == null || entry.preInitOnly) return@synchronized false
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

    /** 校验空闲/推送中的日志流会话是否仍有效(不刷新 lastSeenAtNanos,避免空闲 WS 无限续期)。 */
    internal fun isLogStreamSessionValid(sessionToken: String?, expectedEpoch: Long): Boolean {
        val now = System.nanoTime()
        val expiredStreams = ArrayList<WsConn>()
        val valid = synchronized(sessionsLock) {
            if (sessionEpoch != expectedEpoch) return@synchronized false
            if (!Config.webuiPasswordInitialized && !Config.corruptRecoveryMode) return@synchronized false
            if (sessionToken != null) {
                val entry = sessions[sessionToken] ?: return@synchronized false
                if (entry.preInitOnly) return@synchronized false
                if (isExpired(entry, now)) {
                    sessions.remove(sessionToken)
                    collectStreamsForSessionLocked(sessionToken, expiredStreams)
                    return@synchronized false
                }
            }
            true
        }
        for (c in expiredStreams) c.closeAsync(1008)
        return valid
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
            .put("recoveryMode", Config.corruptRecoveryMode)
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
        if (Config.corruptRecoveryMode) {
            return error(409, "当前处于损坏配置恢复模式，拒绝覆盖 config.json；请先在文件管理器中修复并保存 config.json")
        }
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
        } catch (err: IllegalStateException) {
            return error(409, err.message ?: "当前处于损坏配置恢复模式")
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

        val streamsToClose = ArrayList<WsConn>()
        synchronized(fileWriteLock) {
            val target = resolveManagedPath(rawPath)
                ?: return error(400, "文件路径无效")
            if (isBlockedManagedPath(target)) {
                return error(403, "不允许通过文件管理器修改受保护文件或 webui 静态资源")
            }
            val activeConfig = isActiveConfigFile(target)
            val allowCreateInRecovery = activeConfig && Config.corruptRecoveryMode && !target.exists()
            if (!target.isFile && !allowCreateInRecovery) return error(404, "文件不存在")
            if (target.isFile && !isEditableText(target)) {
                return error(415, "仅支持编辑不超过 1 MiB 的 UTF-8 文本文件")
            }
            if (target.extension.equals("json", ignoreCase = true)) {
                try {
                    if (activeConfig) JSONObject(content) else org.json.JSONTokener(content).nextValue()
                } catch (t: Throwable) {
                    return error(400, "JSON 格式错误: " + t.message)
                }
            }
            if (target.isFile && expectedModifiedAt >= 0 && target.lastModified() != expectedModifiedAt) {
                return error(409, "文件已被其他程序修改，请重新加载后再保存")
            }
            try {
                if (activeConfig) {
                    val candidate = JSONObject(content)
                    val errors = validateConfigPatch(candidate, validateNetworkFallback = false)
                    if (errors.isNotEmpty()) {
                        return error(400, "配置无效: " + errors.joinToString("；"))
                    }
                    val previous = Config.raw().toString()
                    val wasCorruptRecovery = Config.corruptRecoveryMode
                    // Config.replaceConfig 在锁内还原 "***" 连接密钥、保留既有密码哈希并清除恢复模式
                    Config.replaceConfig(candidate) { commitRoot ->
                        if (wasCorruptRecovery) {
                            synchronized(sessionsLock) {
                                commitRoot()
                                revokeAllSessionsLocked(streamsToClose)
                            }
                        } else {
                            commitRoot()
                        }
                    }
                    runtime = applyRuntimeConfig(previous)
                    if (wasCorruptRecovery) {
                        PicoLog.i("corrupt config recovery mode cleared after restoring config.json; recovery sessions revoked")
                    }
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
        for (c in streamsToClose) c.close()

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
        if (Config.corruptRecoveryMode) {
            return error(409, "当前处于损坏配置恢复模式，拒绝覆盖 config.json；请先在文件管理器中修复并保存 config.json")
        }
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
            if (!pbkdf2Semaphore.tryAcquire()) {
                return error(429, "认证服务繁忙，请稍后再试")
            }
            val toClose = ArrayList<WsConn>()
            val result = try {
                Config.changeWebuiPassword(current, token) { commitRoot ->
                    synchronized(sessionsLock) {
                        commitRoot()
                        revokeAllSessionsLocked(toClose)
                    }
                }
            } finally {
                pbkdf2Semaphore.release()
            }
            for (c in toClose) c.close()
            return when (result) {
                Config.PasswordChangeResult.OK -> {
                    recordAuthSuccess(clientIp)
                    PicoLog.i("webui password changed; all sessions revoked")
                    Response(200, JSONObject().put("ok", true).put("sessionsRevoked", true))
                }
                Config.PasswordChangeResult.INVALID_CURRENT -> {
                    recordAuthFailure(clientIp, countGlobal = true)
                    Response(403, JSONObject().put("ok", false).put("message", "当前密码不正确"))
                }
                Config.PasswordChangeResult.CORRUPT_RECOVERY_MODE ->
                    error(409, "当前处于损坏配置恢复模式，拒绝覆盖 config.json；请先在文件管理器中修复并保存 config.json")
                Config.PasswordChangeResult.NOT_INITIALIZED ->
                    error(409, "控制台密码尚未初始化，请刷新页面重新设置")
                Config.PasswordChangeResult.PERSIST_FAILED ->
                    error(500, "保存控制台密码失败，请检查存储空间或权限")
            }
        }
        if (!pbkdf2Semaphore.tryAcquire()) {
            return error(429, "认证服务繁忙，请稍后再试")
        }
        val toClose = ArrayList<WsConn>()
        var newToken: String? = null
        val initResult = try {
            Config.initializeWebuiPassword(token) { commitRoot ->
                val fresh = generateSessionToken()
                val now = System.nanoTime()
                synchronized(sessionsLock) {
                    commitRoot()
                    // 原子撤销所有预初始化会话并推进 sessionEpoch,再签发常规会话令牌
                    revokeAllSessionsLocked(toClose)
                    insertSessionLocked(fresh, now, preInitOnly = false, evictedStreams = toClose)
                    newToken = fresh
                }
            }
        } finally {
            pbkdf2Semaphore.release()
        }
        for (c in toClose) c.close()
        return when (initResult) {
            Config.PasswordInitResult.OK -> {
                PicoLog.i("webui password initialized; pre-init sessions revoked")
                Response(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("token", newToken ?: "")
                        .put("sessionsRevoked", true)
                )
            }
            Config.PasswordInitResult.ALREADY_INITIALIZED ->
                Response(409, JSONObject().put("ok", false).put("message", "控制台密码已设置，不允许再次修改"))
            Config.PasswordInitResult.CORRUPT_RECOVERY_MODE ->
                error(409, "当前处于损坏配置恢复模式，拒绝覆盖 config.json；请先在文件管理器中修复并保存 config.json")
            Config.PasswordInitResult.PERSIST_FAILED ->
                error(500, "保存控制台密码失败，请检查存储空间或权限")
        }
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
        val hasImage = bytes != null && bytes.isNotEmpty()
        val hasQr = url.isNotEmpty() || hasImage
        val age = if (snap.receivedAtWallMs <= 0L) {
            -1
        } else {
            ((System.nanoTime() - snap.receivedAtNs).coerceAtLeast(0L) / 1_000_000_000L).toInt()
        }
        val expired = snap.expired || (age > QR_TTL_SEC)
        val status = when {
            !hasQr -> "waiting_qr"
            expired -> "expired"
            else -> "ok"
        }
        val image = if (status == "ok" && bytes != null && bytes.isNotEmpty())
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
