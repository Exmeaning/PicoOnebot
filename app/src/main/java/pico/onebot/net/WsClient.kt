package pico.onebot.net

import android.util.Base64
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
import java.io.BufferedInputStream
import java.net.Socket
import java.net.URI
import java.security.SecureRandom

/**
 * 反向 WebSocket:由本端连出去(穿 NAT,真机部署的现实选项)。
 * 断线指数退避重连,直到 [stop]。
 */
class WsClient(
    private val url: String,
    private val accessToken: String,
    private val server: HttpServer,
    private val reconnectInterval: Long = 3000
) {

    @Volatile
    private var running = false

    @Volatile
    private var conn: WsConn? = null

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
        conn?.close()
        try { socket?.close() } catch (_: Throwable) { }
        worker?.interrupt()
    }

    private fun loop() {
        val minBackoff = reconnectInterval.coerceAtLeast(100)
        var backoff = minBackoff
        val maxBackoff = Config.long("reverse_reconnect_max_ms", 60000).coerceAtLeast(minBackoff)
        while (running) {
            try {
                connectOnce()
                backoff = minBackoff
            } catch (t: Throwable) {
                if (running) PicoLog.w("reverse ws $url failed: " + t.javaClass.simpleName + " " + t.message)
            } finally {
                try { socket?.close() } catch (_: Throwable) { }
                socket = null
            }
            if (!running) return
            try {
                Thread.sleep(backoff)
            } catch (t: InterruptedException) {
                return
            }
            backoff = minOf(backoff * 2, maxBackoff)
        }
    }

    private fun connectOnce() {
        val uri = URI(url)
        val secure = uri.scheme.equals("wss", true)
        if (secure) throw UnsupportedOperationException("wss 暂未支持,请用 ws:// 或在前面挂反代")
        val port = if (uri.port > 0) uri.port else 80
        val path = (if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath) +
            (if (uri.rawQuery.isNullOrEmpty()) "" else "?" + uri.rawQuery)

        val sock = Socket()
        socket = sock
        if (!running) return
        sock.connect(java.net.InetSocketAddress(uri.host, port), 10000)
        sock.tcpNoDelay = true
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
        if (!status.contains("101")) {
            sock.close()
            throw java.io.IOException("handshake rejected: $status")
        }
        while (true) {
            val line = WsFrame.readLine(ins) ?: break
            if (line.isEmpty()) break
        }

        val c = WsConn(sock, ins, out, "rws:$url", WsConn.Role.BOTH, true)
        conn = c
        try {
            if (!running) return
            sock.soTimeout = 0
            Transport.add(c)
            c.loop { text -> server.onActionText(c, text) }
        } finally {
            c.close()
            Transport.remove(c)
            conn = null
        }
    }
}
