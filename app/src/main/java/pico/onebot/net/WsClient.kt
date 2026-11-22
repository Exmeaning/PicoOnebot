package pico.onebot.net

import android.util.Base64
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
import java.io.BufferedInputStream
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 反向 WebSocket:由本端连出去(穿 NAT,真机部署的现实选项)。
 * 断线指数退避重连,直到 [stop]。
 */
class WsClient(
    private val url: String,
    private val accessToken: String,
    private val server: HttpServer,
    private val reconnectInterval: Long = 3000,
    /** 对应的网络配置项 id,WebUI 按它回报这条反向连接的真实状态。 */
    private val configId: String = ""
) {

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    @Volatile
    private var running = false

    @Volatile
    private var conn: WsConn? = null

    /** 握手完成且尚未断开才算连上 —— 配置里 enabled 不等于连上了。 */
    val connected: Boolean get() = conn?.closed == false

    /** 当前这条连接建立的时刻;没连上是 0。 */
    @Volatile
    var connectedAt: Long = 0
        private set

    @Volatile
    private var socket: Socket? = null

    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread({ loop() }, "pico-rws").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        connectedAt = 0
        conn?.close()
        try { socket?.close() } catch (_: Throwable) { }
        worker?.interrupt()
    }

    private fun loop() {
        val minBackoff = reconnectInterval.coerceAtLeast(100)
        var backoff = minBackoff
        val maxBackoff = Config.long("reverse_reconnect_max_ms", 60000).coerceAtLeast(minBackoff)
        while (running) {
            var establishedAt = 0L
            var failed = false
            try {
                establishedAt = connectOnce()
                backoff = minBackoff
            } catch (t: Throwable) {
                failed = true
                if (running) PicoLog.w("reverse ws $url failed: " + t.javaClass.simpleName + " " + t.message)
            } finally {
                try { socket?.close() } catch (_: Throwable) { }
                socket = null
            }
            if (!running) return
            val lastedMs = if (establishedAt > 0L) System.currentTimeMillis() - establishedAt else 0L
            val sleepMs = if (!failed && lastedMs >= 5_000L) {
                200L
            } else {
                val cur = backoff
                backoff = minOf(backoff * 2, maxBackoff)
                cur
            }
            try {
                Thread.sleep(sleepMs)
            } catch (t: InterruptedException) {
                return
            }
        }
    }

    private fun connectOnce(): Long {
        val uri = URI(url)
        val secure = uri.scheme.equals("wss", true)
        if (secure) throw UnsupportedOperationException("wss 暂未支持,请用 ws:// 或在前面挂反代")
        val port = if (uri.port > 0) uri.port else 80
        val path = (if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath) +
            (if (uri.rawQuery.isNullOrEmpty()) "" else "?" + uri.rawQuery)

        val sock = Socket()
        socket = sock
        if (!running) return 0L
        sock.connect(java.net.InetSocketAddress(uri.host, port), 10000)
        sock.tcpNoDelay = true
        sock.keepAlive = true
        sock.soTimeout = 10000

        val keyBytes = ByteArray(16)
        SecureRandom().nextBytes(keyBytes)
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

        val req = StringBuilder()
            .append("GET ").append(path).append(" HTTP/1.1\r\n")
            .append("Host: ").append(uri.host).append(":").append(port).append("\r\n")
            .append("Upgrade: websocket\r\n")
            .append("Connection: Upgrade\r\n")
            .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            .append("Sec-WebSocket-Version: 13\r\n")
            .append("User-Agent: PicoOnebot/").append(pico.onebot.PicoBoot.VERSION).append("\r\n")
            .append("X-Client-Role: Universal\r\n")
            .append("X-Self-ID: ").append(KernelGate.selfUin()).append("\r\n")
            .apply {
                if (accessToken.isNotEmpty()) {
                    append("Authorization: Bearer ").append(accessToken).append("\r\n")
                }
            }
            .append("\r\n")
            .toString()

        val out = sock.getOutputStream()
        out.write(req.toByteArray(Charsets.UTF_8))
        out.flush()

        val ins = BufferedInputStream(sock.getInputStream(), 8192)
        val status = WsFrame.readLine(ins) ?: throw java.io.EOFException("no handshake response")
        // 必须精确匹配状态码:用 contains("101") 时,
        // "404 Not Found (see /docs/101-x)" 这类状态行也会被判成握手成功。
        val statusCode = status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        if (statusCode != 101) {
            sock.close()
            throw java.io.IOException("handshake rejected: $status")
        }
        var acceptHeader: String? = null
        while (true) {
            val line = WsFrame.readLine(ins) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Sec-WebSocket-Accept", ignoreCase = true)) {
                acceptHeader = line.substring(idx + 1).trim()
            }
        }
        // RFC 6455 §4.1:客户端必须校验 Accept 摘要,否则可能把非 WS 对端当成 WS。
        // 响应里**缺少**这个头同样是握手失败 —— 曾写成
        // `acceptHeader != null && acceptHeader != expected`,省略该头的 101 响应会被放行。
        val expectedAccept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        if (acceptHeader != expectedAccept) {
            sock.close()
            throw java.io.IOException("handshake rejected: bad or missing Sec-WebSocket-Accept")
        }

        val c = WsConn(sock, ins, out, "rws:$url", WsConn.Role.BOTH, true, configId)
        val establishedAt = System.currentTimeMillis()
        conn = c
        connectedAt = establishedAt
        try {
            if (!running) return establishedAt
            Transport.add(c)
            c.loop { text -> server.onActionText(c, text) }
        } finally {
            c.close()
            Transport.remove(c)
            conn = null
            connectedAt = 0
        }
        return establishedAt
    }
}
