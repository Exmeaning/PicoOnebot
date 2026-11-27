package pico.onebot.net

import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import pico.onebot.action.ActionRouter
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.proto.Ob
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一个端口同时承载:正向 WebSocket(`/`、`/api`、`/event`)、
 * HTTP API(`POST /{action}`、`GET /{action}?a=b`)、控制台静态文件与 `/api/pico/…`。
 *
 * 线程模型:一个 accept 线程 + 有界连接线程池(防线程耗尽与 OOM 击垮 accept 循环)。
 */
class HttpServer(
    val host: String,
    val port: Int,
    val role: Role = Role.ONEBOT,
    /** ONEBOT 角色的鉴权口令；WEBUI 角色使用 Config 中的密码派生值校验。 */
    private val serverToken: String = "",
    /** 起这个监听的网络配置项 id,接进来的连接都挂在它名下。 */
    val configId: String = "",
    /** 是否回通配 CORS 头(仅 ONEBOT 显式配置 cors=true 且有 token 时回 *);默认关闭。 */
    val allowCors: Boolean = false,
    /** 是否接受 WebSocket 升级(连接项里的 websocket 开关)。 */
    val allowWebSocket: Boolean = true,
    /** 可选的精确 CORS Origin 白名单提供者(如 webui.cors_allowed_origins)。 */
    private val corsAllowedOriginsProvider: () -> List<String> = { emptyList() }
) {

    enum class Role { ONEBOT, WEBUI }

    private class HttpError(val status: Int, message: String) : IOException(message)

    /** 峰值内存受控的字节缓冲:增长步长上限 1 MiB 且绝不超过 maxBytes,满容量时零拷贝返回底层数组。 */
    private class BoundedByteArrayOutputStream(private val maxBytes: Int) {
        private var buf = ByteArray(minOf(4096, maxBytes.coerceAtLeast(1)))
        var size: Int = 0
            private set

        fun write(src: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val needed = size + len
            if (needed > maxBytes || needed < 0) {
                throw HttpError(413, "chunked body too large")
            }
            if (needed > buf.size) {
                val step = minOf(buf.size, 1024 * 1024)
                val grown = minOf(maxBytes, maxOf(needed, buf.size + step))
                buf = buf.copyOf(grown)
            }
            System.arraycopy(src, off, buf, size, len)
            size = needed
        }

        fun toByteArray(): ByteArray = if (size == buf.size) buf else buf.copyOf(size)
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val HTTP_READ_TIMEOUT_MS = 30_000
        const val HEADER_DEADLINE_MS = 15_000L
        const val BODY_DEADLINE_MS = 30_000L
        const val MAX_HEADERS = 64
        const val MAX_HEADER_BYTES = 32 * 1024
        const val MAX_CHUNK_TRAILERS = 32
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
        const val MAX_WEBUI_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_LOGIN_BODY_BYTES = 64 * 1024
        const val MAX_STATIC_BYTES = 16L * 1024 * 1024
        const val MAX_CONCURRENT_CONNS = 64
        const val MAX_CONNS_PER_IP = 16
        const val MAX_UNAUTH_INFLIGHT = 16

        val actionThreadSeq = AtomicInteger()
        val connThreadSeq = AtomicInteger()
        val actionExecutor = ThreadPoolExecutor(
            8,
            16,
            60L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(256),
            ThreadFactory { r ->
                Thread(r, "pico-ws-act-" + actionThreadSeq.incrementAndGet()).apply { isDaemon = true }
            }
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private val connExecutor = ThreadPoolExecutor(
        0,
        MAX_CONCURRENT_CONNS,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { r ->
            Thread(r, "pico-conn-" + connThreadSeq.incrementAndGet()).apply { isDaemon = true }
        }
    )

    private val ipConnLock = Any()
    private val ipConnCounts = HashMap<String, Int>()
    private val unauthInflight = Semaphore(MAX_UNAUTH_INFLIGHT)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var running = false
        private set

    @Volatile
    private var transportEpoch: Long = -1L

    fun start() {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName(host), port))
        } catch (t: Throwable) {
            // bind 失败时这个 ServerSocket 还没交给任何人,不关就会泄漏 FD。
            try {
                s.close()
            } catch (_: Throwable) {
            }
            throw t
        }
        server = s
        transportEpoch = Transport.currentEpoch()
        running = true
        Thread({ acceptLoop(s) }, "pico-http-accept").apply { isDaemon = true }.start()
        PicoLog.i(role.name.lowercase() + " http/ws listening on $host:$port")
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        if (role == Role.WEBUI) {
            WebApi.closeAllLogStreams()
        }
        try {
            connExecutor.shutdown()
        } catch (_: Throwable) {
        }
    }

    private fun tryAcquireIpSlot(ip: String): Boolean = synchronized(ipConnLock) {
        val cur = ipConnCounts[ip] ?: 0
        if (cur >= MAX_CONNS_PER_IP) return false
        ipConnCounts[ip] = cur + 1
        true
    }

    private fun releaseIpSlot(ip: String) {
        synchronized(ipConnLock) {
            val cur = ipConnCounts[ip] ?: return
            if (cur <= 1) ipConnCounts.remove(ip) else ipConnCounts[ip] = cur - 1
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        var consecutiveErrors = 0
        while (running) {
            val sock = try {
                val accepted = s.accept()
                consecutiveErrors = 0
                accepted
            } catch (t: Throwable) {
                if (!running || s.isClosed) break
                consecutiveErrors++
                val backoffMs = minOf(50L shl (consecutiveErrors - 1).coerceAtMost(6), 2_000L)
                if (consecutiveErrors <= 3 || consecutiveErrors % 20 == 0) {
                    PicoLog.w("accept failed (#$consecutiveErrors): " + t.message)
                }
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    if (!running) break
                }
                continue
            }
            val peerIp = sock.inetAddress?.hostAddress ?: "unknown"
            if (!tryAcquireIpSlot(peerIp)) {
                PicoLog.w("per-IP connection limit ($MAX_CONNS_PER_IP) reached for $peerIp on $host:$port; rejected")
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
                continue
            }
            try {
                connExecutor.execute {
                    try {
                        handle(sock, peerIp)
                    } finally {
                        releaseIpSlot(peerIp)
                    }
                }
            } catch (t: Throwable) {
                releaseIpSlot(peerIp)
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
                if (t is RejectedExecutionException) {
                    PicoLog.w("http connection limit ($MAX_CONCURRENT_CONNS) reached on $host:$port; rejected peer")
                } else {
                    PicoLog.w("http dispatch failed on $host:$port: " + t.javaClass.simpleName + " " + t.message)
                }
            }
        }
    }

    private fun isValidHeaderName(name: String): Boolean {
        if (name.isEmpty()) return false
        for (c in name) {
            val valid = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                c == '!' || c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' ||
                c == '*' || c == '+' || c == '-' || c == '.' || c == '^' || c == '_' ||
                c == '`' || c == '|' || c == '~'
            if (!valid) return false
        }
        return true
    }

    private fun handle(sock: Socket, clientIp: String) {
        var corsOrigin: String? = null
        var varyOrigin = false
        // 无论是 HTTP 还是同步阻塞到结束的 WS 连接,最终都在这里兜底关 socket,绝不泄漏 FD。
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            val headerDeadlineNanos = System.nanoTime() + HEADER_DEADLINE_MS * 1_000_000L
            sock.soTimeout = HEADER_DEADLINE_MS.toInt()
            val ins = BufferedInputStream(sock.getInputStream(), 8192)
            val out = sock.getOutputStream()

            val requestLine = readLineWithDeadline(sock, ins, headerDeadlineNanos) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                writeText(out, 400, "text/plain", "malformed request line")
                return
            }
            val method = parts[0].uppercase(Locale.ROOT)
            val rawPath = parts[1]

            val headers = HashMap<String, String>()
            var headerCount = 0
            var headerBytes = requestLine.length + 2
            var contentLengthSeen: String? = null
            while (true) {
                val line = readLineWithDeadline(sock, ins, headerDeadlineNanos)
                    ?: throw HttpError(400, "truncated request headers")
                if (line.isEmpty()) break
                headerCount++
                headerBytes += line.length + 2
                if (headerCount > MAX_HEADERS || headerBytes > MAX_HEADER_BYTES) {
                    writeText(out, 431, "text/plain", "request headers too large")
                    return
                }
                val idx = line.indexOf(':')
                if (idx <= 0) {
                    writeText(out, 400, "text/plain", "malformed header")
                    return
                }
                val rawName = line.substring(0, idx)
                if (!isValidHeaderName(rawName)) {
                    writeText(out, 400, "text/plain", "malformed header name")
                    return
                }
                val name = rawName.lowercase(Locale.ROOT)
                val value = line.substring(idx + 1).trim()
                if ((name == "host" || name == "authorization") && headers.containsKey(name)) {
                    writeText(out, 400, "text/plain", "duplicate $name header")
                    return
                }
                if (name == "content-length") {
                    if (contentLengthSeen != null && contentLengthSeen != value) {
                        writeText(out, 400, "text/plain", "conflicting Content-Length headers")
                        return
                    }
                    contentLengthSeen = value
                }
                val existing = headers[name]
                headers[name] = if (existing == null || name == "content-length") {
                    value
                } else {
                    "$existing, $value"
                }
            }
            sock.soTimeout = HTTP_READ_TIMEOUT_MS

            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val query = if (qIdx >= 0) parseQuery(rawPath.substring(qIdx + 1)) else HashMap()

            corsOrigin = resolveCorsOrigin(headers["origin"])
            varyOrigin = shouldVaryOrigin(corsOrigin)

            // CORS 预检统一优先处理(不读请求体)
            if (method == "OPTIONS") {
                if (corsOrigin != null) {
                    writeCorsPreflight(out, corsOrigin, varyOrigin)
                } else {
                    writeText(out, 403, "text/plain", "CORS disabled", corsOrigin = null, varyOrigin = varyOrigin)
                }
                return
            }

            val upgrade = headers["upgrade"]?.lowercase(Locale.ROOT)
            if (upgrade == "websocket" && role == Role.ONEBOT && !allowWebSocket) {
                writeText(out, 400, "text/plain", "websocket disabled for this listener", corsOrigin, varyOrigin)
                return
            }
            if (upgrade == "websocket") {
                val wsError = validateWebSocketUpgradeHeaders(method, headers)
                if (wsError != null) {
                    writeText(out, 400, "text/plain", wsError, corsOrigin, varyOrigin)
                    return
                }
                val origin = headers["origin"]
                val secFetchSite = headers["sec-fetch-site"]?.trim()?.lowercase(Locale.ROOT)
                val enforceOrigin = !allowCors || (role == Role.ONEBOT && serverToken.isBlank())
                if (enforceOrigin) {
                    if (origin != null && !isAllowedOrigin(origin, headers["host"])) {
                        writeText(out, 403, "text/plain", "forbidden origin", corsOrigin, varyOrigin)
                        return
                    }
                    if (origin == null && role == Role.ONEBOT && serverToken.isBlank() && secFetchSite == "cross-site") {
                        writeText(out, 403, "text/plain", "forbidden cross-site request", corsOrigin, varyOrigin)
                        return
                    }
                }
                if (role == Role.WEBUI) {
                    if (path.trimEnd('/') != "/api/logs/stream") {
                        writeText(out, 404, "text/plain", "unknown websocket route", corsOrigin, varyOrigin)
                        return
                    }
                    val auth = WebApi.checkAuthorization(headers, query, clientIp, allowQuerySession = true)
                    if (auth.rateLimited) {
                        writeText(out, 429, "text/plain", auth.rateLimitMessage ?: "too many auth attempts", corsOrigin, varyOrigin)
                        return
                    }
                    if (!auth.authorized) {
                        writeText(out, 401, "text/plain", "unauthorized", corsOrigin, varyOrigin)
                        return
                    }
                    if (auth.preInitOnly || (!Config.webuiPasswordInitialized && !Config.corruptRecoveryMode)) {
                        writeText(
                            out,
                            428,
                            "application/json",
                            JSONObject().put("ok", false)
                                .put("mustChangePassword", true)
                                .put("message", "首次使用必须先修改控制台密码")
                                .toString(),
                            corsOrigin,
                            varyOrigin
                        )
                        return
                    }
                    if (WebApi.logStreamCount() >= WebApi.MAX_LOG_STREAMS) {
                        writeText(out, 503, "text/plain", "too many log stream connections", corsOrigin, varyOrigin)
                        return
                    }
                    serveLogStream(sock, ins, out, headers, auth.sessionToken, auth.authEpoch)
                    return
                }
                if (!authorized(headers, query)) {
                    writeText(out, 401, "text/plain", "unauthorized", corsOrigin, varyOrigin)
                    return
                }
                serveWebSocket(sock, ins, out, path, headers)
                return
            }

            // 校验 Framing(如 CL+TE 冲突),但不提前读请求体 —— 先做路由/方法/鉴权再读 body
            validateBodyFraming(headers)

            if (role == Role.WEBUI) {
                serveWebUi(sock, out, ins, method, path, query, headers, clientIp, corsOrigin, varyOrigin)
            } else {
                serveHttp(sock, out, ins, method, path, query, headers, corsOrigin, varyOrigin)
            }
        } catch (e: WsFrame.LineTooLongException) {
            try {
                writeText(sock.getOutputStream(), 431, "text/plain", e.message ?: "header line too long", corsOrigin, varyOrigin)
            } catch (_: Throwable) {
            }
        } catch (e: SocketTimeoutException) {
            try {
                writeText(sock.getOutputStream(), 408, "text/plain", "request timeout", corsOrigin, varyOrigin)
            } catch (_: Throwable) {
            }
        } catch (e: HttpError) {
            try {
                writeText(sock.getOutputStream(), e.status, "text/plain", e.message ?: "http error", corsOrigin, varyOrigin)
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            PicoLog.d("conn error: " + t.javaClass.simpleName + " " + t.message)
        } finally {
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readLineWithDeadline(sock: Socket, ins: InputStream, deadlineNanos: Long): String? {
        val remMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
        if (remMs <= 0L) throw SocketTimeoutException("request deadline exceeded")
        sock.soTimeout = remMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return WsFrame.readLine(ins, WsFrame.MAX_LINE_BYTES, deadlineNanos)
    }

    private fun readFullyWithDeadline(
        sock: Socket,
        ins: InputStream,
        buf: ByteArray,
        offset: Int,
        length: Int,
        deadlineNanos: Long
    ) {
        var off = offset
        val end = offset + length
        while (off < end) {
            val remMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
            if (remMs <= 0L) throw SocketTimeoutException("request body deadline exceeded")
            sock.soTimeout = remMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val n = ins.read(buf, off, end - off)
            if (n < 0) throw EOFException("stream closed")
            off += n
        }
    }

    // ---------------- WebSocket ----------------

    private fun validateWebSocketUpgradeHeaders(method: String, headers: Map<String, String>): String? {
        if (method != "GET") return "websocket upgrade requires GET"
        val connHeader = headers["connection"] ?: ""
        val hasUpgradeToken = connHeader.split(',').any { it.trim().equals("upgrade", ignoreCase = true) }
        if (!hasUpgradeToken) return "missing Connection: Upgrade"
        val version = headers["sec-websocket-version"]?.trim()
        if (version != "13") return "unsupported Sec-WebSocket-Version"
        if (headers["sec-websocket-key"].isNullOrBlank()) return "missing Sec-WebSocket-Key"
        return null
    }

    /** 写 101 Switching Protocols;缺 key 时写 400 并返回 false。 */
    private fun wsHandshake(out: OutputStream, headers: Map<String, String>): Boolean {
        val key = headers["sec-websocket-key"]
        if (key.isNullOrBlank()) {
            writeText(out, 400, "text/plain", "missing Sec-WebSocket-Key")
            return false
        }
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        val resp = StringBuilder()
            .append("HTTP/1.1 101 Switching Protocols\r\n")
            .append("Upgrade: websocket\r\n")
            .append("Connection: Upgrade\r\n")
            .append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
            .append("\r\n")
            .toString()
        out.write(resp.toByteArray(Charsets.UTF_8))
        out.flush()
        return true
    }

    /** WebUI 的实时日志流:握手后订阅 PicoLog,每条新日志推一帧,并周期/写前校验会话 TTL,直到断开或过期。 */
    private fun serveLogStream(
        sock: Socket,
        ins: InputStream,
        out: OutputStream,
        headers: Map<String, String>,
        sessionToken: String?,
        authEpoch: Long
    ) {
        if (!wsHandshake(out, headers)) return
        var conn: WsConn? = null
        var unsub: (() -> Unit)? = null
        try {
            val c = WsConn(
                sock,
                ins,
                out,
                (sock.inetAddress?.hostAddress ?: "unknown") + ":" + sock.port,
                WsConn.Role.EVENT,
                false,
                onPingCheck = { WebApi.isLogStreamSessionValid(sessionToken, authEpoch) }
            )
            conn = c
            if (!running || !WebApi.registerLogStream(c, sessionToken, authEpoch)) {
                return
            }
            // 先补发最近若干条,前端一连上就有内容
            for (e in PicoLog.entries(200)) {
                if (!WebApi.isLogStreamSessionValid(sessionToken, authEpoch)) {
                    c.closeAsync(1008)
                    return
                }
                if (!c.sendText(e.toJson().toString())) break
            }
            unsub = PicoLog.subscribe { entry ->
                if (!WebApi.isLogStreamSessionValid(sessionToken, authEpoch)) {
                    c.closeAsync(1008)
                    return@subscribe
                }
                c.sendText(entry.toJson().toString())
            }
            c.loop { /* 日志流是单向推送,忽略入站文本 */ }
        } finally {
            try {
                unsub?.invoke()
            } catch (_: Throwable) {
            }
            conn?.let {
                WebApi.unregisterLogStream(it)
                it.close()
            }
        }
    }

    /** WebUI 端口的 HTTP:`/api/…` 交给 [WebApi],其余当静态页(内嵌 WebUI)。 */
    private fun serveWebUi(
        sock: Socket,
        out: OutputStream,
        ins: InputStream,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        clientIp: String,
        corsOrigin: String?,
        varyOrigin: Boolean
    ) {
        if (path.startsWith("/api/")) {
            if (!WebApi.isKnownRoute(method, path)) {
                writeText(
                    out,
                    404,
                    "application/json",
                    JSONObject().put("ok", false).put("message", "unknown route: $path").toString(),
                    corsOrigin,
                    varyOrigin
                )
                return
            }
            val isMutating = method == "POST" || method == "PUT"
            if (isMutating) {
                val origin = headers["origin"]
                if (origin != null && !allowCors && !isAllowedOrigin(origin, headers["host"])) {
                    writeText(
                        out,
                        403,
                        "application/json",
                        JSONObject().put("ok", false).put("message", "forbidden cross-origin request").toString(),
                        corsOrigin,
                        varyOrigin
                    )
                    return
                }
                val hasBody = hasRequestBody(headers)
                if ((hasBody || WebApi.requiresJsonBody(method, path)) && !isJsonContentType(headers["content-type"])) {
                    writeText(
                        out,
                        415,
                        "application/json",
                        JSONObject().put("ok", false).put("message", "Content-Type must be application/json").toString(),
                        corsOrigin,
                        varyOrigin
                    )
                    return
                }
            }

            // 读请求体前仅做廉价 O(1) 拒绝(无头/锁定/预初始化禁止路由),绝不免除读体后的完整鉴权
            val isLogin = path == "/api/auth/login" && method == "POST"
            val preCheck = if (isLogin) null else WebApi.preCheckRequest(method, path, headers, clientIp)
            if (preCheck != null && preCheck.status != 200) {
                val payload = JSONObject().put("ok", false).put("message", preCheck.message ?: "unauthorized")
                if (preCheck.mustChangePassword) payload.put("mustChangePassword", true)
                writeText(out, preCheck.status, "application/json", payload.toString(), corsOrigin, varyOrigin)
                return
            }

            val needsUnauthSlot = isLogin || (isMutating && preCheck?.isAuthenticatedSession != true)
            if (needsUnauthSlot && !unauthInflight.tryAcquire()) {
                writeText(
                    out,
                    503,
                    "application/json",
                    JSONObject().put("ok", false).put("message", "未鉴权并发请求过多，请稍后再试").toString(),
                    corsOrigin,
                    varyOrigin
                )
                return
            }
            val r = try {
                val bodyLimit = if (isLogin) MAX_LOGIN_BODY_BYTES else MAX_WEBUI_BODY_BYTES
                val body = if (isMutating) readBody(sock, ins, headers, bodyLimit) else ByteArray(0)
                WebApi.handle(
                    method,
                    path,
                    query,
                    headers,
                    body,
                    clientIp,
                    expectedSessionEpoch = preCheck?.expectedEpoch
                )
            } finally {
                if (needsUnauthSlot) unauthInflight.release()
            }
            val text = if (r.json.has("_array")) r.json.getJSONArray("_array").toString() else r.json.toString()
            writeText(out, r.status, "application/json", text, corsOrigin, varyOrigin)
            return
        }
        if (method == "GET" && serveStatic(out, path, corsOrigin, varyOrigin)) return
        writeText(out, 404, "text/plain", "not found: $path", corsOrigin, varyOrigin)
    }

    private fun serveWebSocket(
        sock: Socket,
        ins: InputStream,
        out: OutputStream,
        path: String,
        headers: Map<String, String>
    ) {
        if (!wsHandshake(out, headers)) return

        val role = when (path.trimEnd('/')) {
            "/api" -> WsConn.Role.API
            "/event" -> WsConn.Role.EVENT
            else -> WsConn.Role.BOTH
        }
        var conn: WsConn? = null
        var added = false
        try {
            val c = WsConn(sock, ins, out, (sock.inetAddress?.hostAddress ?: "unknown") + ":" + sock.port, role, false, configId)
            conn = c
            if (!running || !Transport.add(c, transportEpoch)) {
                return
            }
            added = true
            c.loop { text -> onActionText(c, text) }
        } finally {
            conn?.let {
                if (added) Transport.remove(it)
                it.close()
            }
        }
    }

    /** 收到一条动作请求文本(正向 WS / 反向 WS 共用);派发到工作线程池避免阻塞 WS 读循环。 */
    fun onActionText(conn: WsConn, text: String) {
        if (!conn.wantsActions || conn.closed) return
        try {
            actionExecutor.execute {
                try {
                    if (conn.closed) return@execute
                    val reply = handleActionJson(text)
                    if (reply != null && !conn.closed) conn.sendText(reply.toString())
                } catch (t: Throwable) {
                    PicoLog.w("ws action dispatch failed: " + t.message)
                }
            }
        } catch (_: Throwable) {
            PicoLog.w("ws action queue full on " + conn.tag)
            val echo = try { JSONObject(text).opt("echo") } catch (_: Throwable) { null }
            val err = Ob.failed(10001, "action executor busy")
            if (echo != null) err.put("echo", echo)
            if (!conn.closed) conn.sendText(err.toString())
        }
    }

    // ---------------- HTTP ----------------

    private fun serveHttp(
        sock: Socket,
        out: OutputStream,
        ins: InputStream,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        corsOrigin: String?,
        varyOrigin: Boolean
    ) {
        if (path.startsWith("/api/pico")) {
            // 管理接口比普通 action 敏感得多(能改配置、读密钥、踢下线)。
            // 即使监听项被误配成空 token,也不允许无凭证访问。
            if (serverToken.isBlank() || !authorized(headers, query)) {
                writeText(out, 401, "application/json", Ob.failed(1403, "unauthorized").toString(), corsOrigin, varyOrigin)
                return
            }
            val subPath = path.removePrefix("/api/pico")
            val body = if (method == "POST" || method == "PUT") readBody(sock, ins, headers, MAX_WEBUI_BODY_BYTES) else ByteArray(0)
            val resp = AdminApi.handle(subPath, query, body, method)
            val status = if (resp.optInt("retcode", 0) == Ob.RC_BAD_PARAM &&
                resp.optString("message", "").startsWith("method not allowed")
            ) 405 else 200
            writeText(out, status, "application/json", resp.toString(), corsOrigin, varyOrigin)
            return
        }

        // 落地媒体的字节流: 使用 128-bit SecureRandom 能力令牌鉴权(无需 ?access_token=, 若带了也兼容放行)。
        if (path.startsWith("/media/")) {
            if (method != "GET") {
                writeText(out, 405, "application/json", Ob.failed(1404, "method not allowed").toString(), corsOrigin, varyOrigin)
                return
            }
            val token = path.removePrefix("/media/")
            if (token.isEmpty() || token.indexOf('/') >= 0 || token.indexOf('\\') >= 0) {
                writeText(out, 404, "application/json", Ob.failed(1404, "media not found").toString(), corsOrigin, varyOrigin)
                return
            }
            val f = pico.onebot.media.MediaFiles.lookup(token)
            if (f == null) {
                writeText(out, 404, "application/json", Ob.failed(1404, "media not found").toString(), corsOrigin, varyOrigin)
            } else {
                writeFile(out, f, corsOrigin, varyOrigin)
            }
            return
        }

        val action = path.trim('/')
        if (action.isNotEmpty() && ActionRouter.has(action)) {
            if (serverToken.isBlank()) {
                // 无 token 的本机 OneBot HTTP 监听必须拦截跨站浏览器 CSRF 请求
                val origin = headers["origin"]
                val secFetchSite = headers["sec-fetch-site"]?.trim()?.lowercase(Locale.ROOT)
                if (origin != null && !isAllowedOrigin(origin, headers["host"])) {
                    writeText(out, 403, "application/json", Ob.failed(1403, "forbidden cross-origin request").toString(), corsOrigin, varyOrigin)
                    return
                }
                if (secFetchSite == "cross-site") {
                    writeText(out, 403, "application/json", Ob.failed(1403, "forbidden cross-site request").toString(), corsOrigin, varyOrigin)
                    return
                }
            }
            if (!authorized(headers, query)) {
                writeText(out, 401, "application/json", Ob.failed(1403, "unauthorized").toString(), corsOrigin, varyOrigin)
                return
            }
            val needsUnauthSlot = serverToken.isBlank() && method != "GET" && hasRequestBody(headers)
            if (needsUnauthSlot && !unauthInflight.tryAcquire()) {
                writeText(out, 503, "application/json", Ob.failed(1503, "too many concurrent unauthenticated requests").toString(), corsOrigin, varyOrigin)
                return
            }
            val body = try {
                if (method == "GET") ByteArray(0) else readBody(sock, ins, headers, MAX_BODY_BYTES)
            } finally {
                if (needsUnauthSlot) unauthInflight.release()
            }
            val params = try {
                when {
                    body.isNotEmpty() && (headers["content-type"] ?: "").lowercase(Locale.ROOT).contains("json") ->
                        JSONObject(String(body, Charsets.UTF_8))
                    body.isNotEmpty() -> mapToJson(parseQuery(String(body, Charsets.UTF_8)))
                    else -> mapToJson(query)
                }
            } catch (e: JSONException) {
                writeText(out, 400, "application/json", Ob.failed(Ob.RC_BAD_PARAM, "invalid JSON body: " + e.message).toString(), corsOrigin, varyOrigin)
                return
            }
            val resp = ActionRouter.call(action, params)
            writeText(out, 200, "application/json", resp.toString(), corsOrigin, varyOrigin)
            return
        }

        writeText(out, 404, "application/json", Ob.failed(1404, "unknown action or path: $path").toString(), corsOrigin, varyOrigin)
    }

    /**
     * 控制台静态文件。顺序(见 docs/design §3.3):
     * `filesDir/pico-onebot/webui/`(开发覆盖,强制约束在 dataDir/webui 内部且流式限长)
     * → `assets/pico/webui.html`(编译期内嵌单文件)。
     */
    private fun serveStatic(out: OutputStream, path: String, corsOrigin: String?, varyOrigin: Boolean): Boolean {
        val rel = if (path == "/" || path.isEmpty()) "index.html" else path.trimStart('/')
        if (rel.indexOf('\u0000') >= 0) return false
        val allowedBase = try {
            File(Config.dataDir, "webui").canonicalFile
        } catch (_: Throwable) {
            null
        }
        val dir = try {
            Config.webuiDir.canonicalFile
        } catch (_: Throwable) {
            null
        }
        val dirConfined = allowedBase != null && dir != null &&
            (dir.path == allowedBase.path || dir.path.startsWith(allowedBase.path + File.separator))
        if (dirConfined && dir!!.isDirectory) {
            val f = File(dir, rel)
            val root = dir.path
            val target = try { f.canonicalFile } catch (_: Throwable) { return false }
            if (target.path.startsWith(root + File.separator) && target.isFile) {
                writeStaticFile(out, target, mimeOfStatic(rel), corsOrigin, varyOrigin)
                return true
            }
        }
        // 内嵌单文件:任何 html/根路径都回它(WebUI 是 hash 路由的单文件)
        if (rel == "index.html" || rel.endsWith(".html")) {
            val bytes = readEmbeddedWebui()
            if (bytes != null) {
                writeBytes(out, 200, "text/html; charset=utf-8", bytes, corsOrigin, varyOrigin)
                return true
            }
        }
        return false
    }

    private fun writeStaticFile(out: OutputStream, f: File, type: String, corsOrigin: String?, varyOrigin: Boolean) {
        val fis = try {
            FileInputStream(f)
        } catch (_: FileNotFoundException) {
            writeText(out, 404, "text/plain", "not found", corsOrigin, varyOrigin)
            return
        } catch (t: Throwable) {
            PicoLog.d("static file open failed: " + t.message)
            writeText(out, 500, "text/plain", "failed to open static file", corsOrigin, varyOrigin)
            return
        }
        try {
            fis.use { ins ->
                val actualLen = ins.channel.size()
                if (actualLen > MAX_STATIC_BYTES) {
                    writeText(out, 413, "text/plain", "static file too large", corsOrigin, varyOrigin)
                    return
                }
                val head = StringBuilder()
                    .append("HTTP/1.1 200 OK\r\n")
                    .append("Content-Type: ").append(type).append("\r\n")
                    .append("Content-Length: ").append(actualLen).append("\r\n")
                    .apply { appendCommonHeaders(this, type, corsOrigin = corsOrigin, varyOrigin = varyOrigin) }
                    .append("Connection: close\r\n\r\n")
                    .toString()
                out.write(head.toByteArray(Charsets.UTF_8))
                val buf = ByteArray(64 * 1024)
                var remaining = actualLen
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = ins.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            out.flush()
        } catch (t: Throwable) {
            PicoLog.d("static file write failed: " + t.message)
        }
    }

    private fun mimeOfStatic(rel: String): String = when {
        rel.endsWith(".html") -> "text/html; charset=utf-8"
        rel.endsWith(".js") -> "application/javascript; charset=utf-8"
        rel.endsWith(".css") -> "text/css; charset=utf-8"
        rel.endsWith(".json") -> "application/json; charset=utf-8"
        rel.endsWith(".svg") -> "image/svg+xml"
        rel.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }

    private fun readEmbeddedWebui(): ByteArray? {
        return try {
            val ctx = mqq.app.MobileQQ.sMobileQQ ?: return null
            val stream: InputStream = ctx.assets?.open("pico/webui.html") ?: return null
            stream.use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_STATIC_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- 共用 ----------------

    /** 解析一条 OneBot 动作请求(WS 文本帧),返回应带 echo 的响应。 */
    fun handleActionJson(text: String): JSONObject? {
        return try {
            val req = JSONObject(text)
            val action = req.optString("action", "")
            if (action.isEmpty()) return null
            val params = req.optJSONObject("params") ?: JSONObject()
            val resp = ActionRouter.call(action, params)
            if (req.has("echo")) resp.put("echo", req.get("echo"))
            resp
        } catch (t: Throwable) {
            PicoLog.w("bad action payload: " + t.message)
            Ob.failed(10001, "invalid request: " + t.message)
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun authorized(headers: Map<String, String>, query: Map<String, String>): Boolean {
        val token = serverToken
        if (token.isEmpty()) return true
        val auth = headers["authorization"] ?: ""
        if (auth.startsWith("Bearer ", ignoreCase = true) && constantTimeEquals(auth.substring(7), token)) return true
        if (auth.isNotEmpty() && constantTimeEquals(auth, token)) return true
        val q = query["access_token"]
        return q != null && constantTimeEquals(q, token)
    }

    private fun isJsonContentType(contentType: String?): Boolean {
        if (contentType.isNullOrBlank()) return false
        val mediaType = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return mediaType == "application/json" || mediaType.endsWith("+json")
    }

    private fun matchesConfiguredCorsOrigin(origin: String): Boolean {
        val parsed = Config.normalizeOrigin(origin, allowTrailingSlash = false) ?: return false
        val allowed = corsAllowedOriginsProvider()
        if (allowed.isEmpty()) return false
        for (entry in allowed) {
            val norm = Config.normalizeOrigin(entry, allowTrailingSlash = true) ?: continue
            if (parsed == norm) return true
        }
        return false
    }

    /** 计算应回写的 Access-Control-Allow-Origin 值: 精确白名单优先回显 Origin, OneBot 显式 cors=true(且非空 token)回 *。 */
    private fun resolveCorsOrigin(originHeader: String?): String? {
        if (originHeader != null && matchesConfiguredCorsOrigin(originHeader)) {
            return originHeader.trim()
        }
        if (allowCors && !(role == Role.ONEBOT && serverToken.isBlank())) {
            return "*"
        }
        return null
    }

    private fun shouldVaryOrigin(corsOrigin: String?): Boolean =
        (corsOrigin != null && corsOrigin != "*") || corsAllowedOriginsProvider().isNotEmpty()

    /**
     * 严格校验 Origin: 匹配显式白名单(精确 scheme+host+port)或严格匹配请求 Host 的同源(scheme+host+port,默认端口 80/443 归一化)。
     */
    private fun isAllowedOrigin(origin: String, hostHeader: String?): Boolean {
        val orig = Config.normalizeOrigin(origin, allowTrailingSlash = false) ?: return false
        if (matchesConfiguredCorsOrigin(origin)) return true
        if (hostHeader.isNullOrBlank()) return false
        val (reqHost, reqPort) = parseHostHeader(hostHeader) ?: return false
        if (orig.host != reqHost) return false
        return if (reqPort != null) {
            if (orig.port != reqPort) return false
            when (reqPort) {
                80 -> orig.scheme == "http"
                443 -> orig.scheme == "https"
                else -> true
            }
        } else {
            val defaultPort = if (orig.scheme == "https") 443 else 80
            orig.port == defaultPort
        }
    }

    private fun parseHostHeader(raw: String): Pair<String, Int?>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end <= 1) return null
            val host = s.substring(1, end).lowercase(Locale.ROOT)
            val rest = s.substring(end + 1)
            if (rest.isEmpty()) return host to null
            if (!rest.startsWith(":")) return null
            val p = rest.substring(1).toIntOrNull() ?: return null
            if (p !in 1..65535) return null
            return host to p
        }
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.indexOf(':') == colon) {
            val host = s.substring(0, colon).lowercase(Locale.ROOT)
            val p = s.substring(colon + 1).toIntOrNull() ?: return null
            if (p !in 1..65535) return null
            return host to p
        }
        return s.lowercase(Locale.ROOT) to null
    }

    private fun parseStrictContentLength(raw: String): Long {
        if (raw.isEmpty() || !raw.all { it in '0'..'9' }) {
            throw HttpError(400, "invalid Content-Length")
        }
        return raw.toLongOrNull() ?: throw HttpError(413, "Content-Length exceeds limit")
    }

    private fun validateBodyFraming(headers: Map<String, String>) {
        val hasTe = headers.containsKey("transfer-encoding")
        val hasCl = headers.containsKey("content-length")
        if (hasTe && hasCl) {
            throw HttpError(400, "conflicting Transfer-Encoding and Content-Length")
        }
        if (hasTe) {
            val codings = headers["transfer-encoding"]!!
                .split(',')
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
            if (codings.size != 1 || codings[0] != "chunked") {
                throw HttpError(400, "unsupported Transfer-Encoding")
            }
        }
        if (hasCl) {
            parseStrictContentLength(headers["content-length"]!!.trim())
        }
    }

    private fun hasRequestBody(headers: Map<String, String>): Boolean {
        if (headers.containsKey("transfer-encoding")) return true
        val cl = headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        return cl > 0L
    }

    /**
     * 读请求体(单调时钟总墙钟上限 BODY_DEADLINE_MS,每次 read 前动态收缩 soTimeout):
     * 对非法/超长 Content-Length 显式抛 HttpError(400/413),绝不静默截断或降级成空体。
     */
    private fun readBody(sock: Socket, ins: InputStream, headers: Map<String, String>, maxBytes: Int): ByteArray {
        val deadlineNanos = System.nanoTime() + BODY_DEADLINE_MS * 1_000_000L
        val te = headers["transfer-encoding"]
        if (te != null) return readChunkedBody(sock, ins, maxBytes, deadlineNanos)
        val rawLen = headers["content-length"]?.trim() ?: return ByteArray(0)
        val len = parseStrictContentLength(rawLen)
        if (len == 0L) return ByteArray(0)
        if (len > maxBytes.toLong()) throw HttpError(413, "request body exceeds $maxBytes bytes")
        val buf = ByteArray(len.toInt())
        try {
            readFullyWithDeadline(sock, ins, buf, 0, buf.size, deadlineNanos)
        } catch (_: EOFException) {
            throw HttpError(400, "truncated request body")
        }
        return buf
    }

    /**
     * 解析 chunked 编码,严格校验块长、总长、块尾空行、trailer 上限与总墙钟截止时间,并复用分片缓冲降低峰值内存。
     */
    private fun readChunkedBody(sock: Socket, ins: InputStream, maxBytes: Int, deadlineNanos: Long): ByteArray {
        val out = BoundedByteArrayOutputStream(maxBytes)
        val scratch = ByteArray(16 * 1024)
        while (true) {
            val sizeLine = readLineWithDeadline(sock, ins, deadlineNanos)
                ?: throw HttpError(400, "chunked body truncated: missing size line")
            val hex = sizeLine.substringBefore(';').trim()
            if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw HttpError(400, "chunked body malformed: bad chunk size '$hex'")
            }
            val size = hex.toLongOrNull(16)
                ?: throw HttpError(413, "chunked body too large")
            if (size > maxBytes.toLong()) {
                throw HttpError(413, "chunked body too large")
            }
            if (size == 0L) {
                var trailers = 0
                var trailerBytes = 0
                while (true) {
                    val trailer = readLineWithDeadline(sock, ins, deadlineNanos)
                        ?: throw HttpError(400, "chunked body truncated: missing trailer terminator")
                    if (trailer.isEmpty()) break
                    trailerBytes += trailer.length + 2
                    if (++trailers > MAX_CHUNK_TRAILERS || trailerBytes > MAX_HEADER_BYTES) {
                        throw HttpError(431, "too many chunked trailers")
                    }
                }
                break
            }
            if (out.size.toLong() + size > maxBytes.toLong()) {
                throw HttpError(413, "chunked body too large")
            }
            var remaining = size.toInt()
            try {
                while (remaining > 0) {
                    val toRead = minOf(scratch.size, remaining)
                    readFullyWithDeadline(sock, ins, scratch, 0, toRead, deadlineNanos)
                    out.write(scratch, 0, toRead)
                    remaining -= toRead
                }
            } catch (_: EOFException) {
                throw HttpError(400, "chunked body truncated")
            }
            val term = readLineWithDeadline(sock, ins, deadlineNanos)
            if (term == null || term.isNotEmpty()) {
                throw HttpError(400, "chunked body malformed: invalid chunk terminator")
            }
        }
        return out.toByteArray()
    }

    private fun parseQuery(q: String): HashMap<String, String> {
        val map = HashMap<String, String>()
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            if (i < 0) {
                map[dec(pair)] = ""
            } else {
                map[dec(pair.substring(0, i))] = dec(pair.substring(i + 1))
            }
        }
        return map
    }

    private fun dec(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Throwable) {
        s
    }

    private fun mapToJson(map: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) if (k != "access_token") o.put(k, v)
        return o
    }

    private fun sanitizeFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val sb = StringBuilder(minOf(base.length, 128))
        for (c in base) {
            if (sb.length >= 128) break
            if (c.code in 0x20..0x7E && c != '"' && c != '\\' && c != '/' && c != ';') {
                sb.append(c)
            }
        }
        val cleaned = sb.toString().trim().trimStart('.')
        return cleaned.ifBlank { "media" }
    }

    private fun writeCorsPreflight(out: OutputStream, corsOrigin: String, varyOrigin: Boolean) {
        try {
            val head = StringBuilder()
                .append("HTTP/1.1 204 No Content\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("X-Frame-Options: DENY\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("Access-Control-Allow-Origin: ").append(corsOrigin).append("\r\n")
                .apply {
                    if (varyOrigin) append("Vary: Origin\r\n")
                }
                .append("Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\n")
                .append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
                .append("Access-Control-Max-Age: 600\r\n")
                .append("Content-Length: 0\r\n")
                .append("Connection: close\r\n\r\n")
                .toString()
            out.write(head.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (t: Throwable) {
            PicoLog.d("cors preflight write failed: " + t.message)
        }
    }

    private fun writeText(
        out: OutputStream,
        code: Int,
        type: String,
        body: String,
        corsOrigin: String? = null,
        varyOrigin: Boolean = false
    ) = writeBytes(out, code, type, body.toByteArray(Charsets.UTF_8), corsOrigin, varyOrigin)

    /** 媒体可能有几十兆,**流式**写出去,不整个读进内存(安卓堆只有几百兆);先打开文件流再写 200 头避免截断响应。 */
    private fun writeFile(out: OutputStream, f: File, corsOrigin: String?, varyOrigin: Boolean) {
        val fis = try {
            FileInputStream(f)
        } catch (_: FileNotFoundException) {
            writeText(out, 404, "application/json", Ob.failed(1404, "media not found").toString(), corsOrigin, varyOrigin)
            return
        } catch (t: Throwable) {
            PicoLog.d("media open failed: " + t.message)
            writeText(out, 500, "application/json", Ob.failed(1500, "media read failed").toString(), corsOrigin, varyOrigin)
            return
        }
        try {
            val safeName = sanitizeFilename(f.name)
            val mime = mimeOf(f.name)
            fis.use { ins ->
                val actualLen = ins.channel.size()
                val head = StringBuilder()
                    .append("HTTP/1.1 200 OK\r\n")
                    .append("Content-Type: ").append(mime).append("\r\n")
                    .append("Content-Length: ").append(actualLen).append("\r\n")
                    .append("Content-Disposition: inline; filename=\"").append(safeName).append("\"\r\n")
                    .apply { appendCommonHeaders(this, mime, noStore = false, corsOrigin = corsOrigin, varyOrigin = varyOrigin) }
                    .append("Connection: close\r\n\r\n")
                    .toString()
                out.write(head.toByteArray(Charsets.UTF_8))
                val buf = ByteArray(64 * 1024)
                var remaining = actualLen
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = ins.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            out.flush()
        } catch (t: Throwable) {
            PicoLog.d("media write failed: " + t.message)
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "amr" -> "audio/amr"
        "silk" -> "audio/silk"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun appendCommonHeaders(
        sb: StringBuilder,
        type: String,
        noStore: Boolean = false,
        corsOrigin: String? = null,
        varyOrigin: Boolean = false
    ) {
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("X-Frame-Options: DENY\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        if (noStore || type.startsWith("application/json", ignoreCase = true)) {
            sb.append("Cache-Control: no-store\r\n")
        }
        if (corsOrigin != null) {
            sb.append("Access-Control-Allow-Origin: ").append(corsOrigin).append("\r\n")
        }
        if (varyOrigin) {
            sb.append("Vary: Origin\r\n")
        }
    }

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        415 -> "Unsupported Media Type"
        428 -> "Precondition Required"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Error"
    }

    private fun writeBytes(
        out: OutputStream,
        code: Int,
        type: String,
        body: ByteArray,
        corsOrigin: String? = null,
        varyOrigin: Boolean = false
    ) {
        try {
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(code).append(' ').append(reasonPhrase(code)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.size).append("\r\n")
                .apply { appendCommonHeaders(this, type, noStore = code >= 400, corsOrigin = corsOrigin, varyOrigin = varyOrigin) }
                .append("Connection: close\r\n\r\n")
                .toString()
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
        } catch (t: Throwable) {
            PicoLog.d("http write failed: " + t.message)
        }
    }
}
