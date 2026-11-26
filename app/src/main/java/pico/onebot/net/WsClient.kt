package pico.onebot.net

import android.os.Build
import android.util.Base64
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 反向 WebSocket:由本端连出去(穿 NAT,真机部署的现实选项)。
 * 支持 `ws://` 与 `wss://`(带 TLS SNI 与主机名校验),断线指数退避重连,直到 [stop]。
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
        const val MAX_HANDSHAKE_HEADERS = 64
        const val MAX_HANDSHAKE_HEADER_BYTES = 32 * 1024
        const val STABLE_CONN_MS = 5_000L
        val rng = SecureRandom()

        fun hasCrLf(s: String): Boolean = s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0

        fun isIpLiteral(host: String): Boolean {
            val h = host.removePrefix("[").removeSuffix("]")
            if (h.contains(':')) return true
            val parts = h.split('.')
            return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
        }

        fun safeUrl(raw: String): String = try {
            val u = URI(raw)
            val scheme = u.scheme ?: ""
            val host = u.host ?: ""
            val portPart = if (u.port > 0) ":${u.port}" else ""
            val pathPart = u.rawPath ?: ""
            if (scheme.isNotEmpty() && host.isNotEmpty()) "$scheme://$host$portPart$pathPart"
            else raw.substringBefore('?').substringBefore('#')
        } catch (_: Throwable) {
            raw.substringBefore('?').substringBefore('#')
        }
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

    @Volatile
    private var transportEpoch: Long = -1L

    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        transportEpoch = Transport.currentEpoch()
        running = true
        worker = Thread({ loop() }, "pico-rws").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        connectedAt = 0
        conn?.close()
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        val minBackoff = reconnectInterval.coerceAtLeast(100)
        var backoff = minBackoff
        val maxBackoff = Config.long("reverse_reconnect_max_ms", 60000).coerceAtLeast(minBackoff)
        val redactedUrl = safeUrl(url)
        while (running) {
            var establishedAtNanos: Long? = null
            var failed = false
            try {
                establishedAtNanos = connectOnce(redactedUrl)
            } catch (t: Throwable) {
                failed = true
                if (running) PicoLog.w("reverse ws $redactedUrl failed: " + t.javaClass.simpleName + " " + t.message)
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }
                socket = null
            }
            if (!running) return
            val lastedMs = if (establishedAtNanos != null) {
                ((System.nanoTime() - establishedAtNanos) / 1_000_000L).coerceAtLeast(0L)
            } else {
                0L
            }
            val sleepMs = if (!failed && lastedMs >= STABLE_CONN_MS) {
                backoff = minBackoff
                200L
            } else {
                val cur = backoff
                backoff = minOf(backoff * 2, maxBackoff)
                val jitterBound = minOf(cur / 4, 1_000L).toInt()
                val jitter = if (jitterBound > 0) rng.nextInt(jitterBound + 1).toLong() else 0L
                cur + jitter
            }
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun connectOnce(redactedUrl: String): Long? {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: ""
        val secure = when (scheme) {
            "ws" -> false
            "wss" -> true
            else -> throw IllegalArgumentException("unsupported websocket scheme '$scheme' (only ws:// and wss://)")
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing host in reverse ws url")
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        if (port !in 1..65535) throw IllegalArgumentException("invalid port: $port")
        val path = (if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath) +
            (if (uri.rawQuery.isNullOrEmpty()) "" else "?" + uri.rawQuery)
        if (hasCrLf(host) || hasCrLf(path) || hasCrLf(accessToken)) {
            throw IllegalArgumentException("CR/LF not allowed in websocket handshake target or accessToken")
        }

        val rawSock = Socket()
        socket = rawSock
        if (!running) {
            try {
                rawSock.close()
            } catch (_: Throwable) {
            }
            return null
        }
        rawSock.connect(InetSocketAddress(host, port), 10_000)

        val sock: Socket = if (secure) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(rawSock, host, port, true) as SSLSocket
            socket = ssl
            ssl.tcpNoDelay = true
            ssl.keepAlive = true
            ssl.soTimeout = 10_000
            configureTls(ssl, host)
            ssl.startHandshake()
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(host, ssl.session)) {
                try {
                    ssl.close()
                } catch (_: Throwable) {
                }
                throw SSLPeerUnverifiedException("TLS hostname verification failed for $host")
            }
            ssl
        } else {
            rawSock.tcpNoDelay = true
            rawSock.keepAlive = true
            rawSock.soTimeout = 10_000
            rawSock
        }

        val keyBytes = ByteArray(16)
        rng.nextBytes(keyBytes)
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

        val defaultPort = if (secure) 443 else 80
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val hostHeader = if (port == defaultPort) formattedHost else "$formattedHost:$port"

        val req = StringBuilder()
            .append("GET ").append(path).append(" HTTP/1.1\r\n")
            .append("Host: ").append(hostHeader).append("\r\n")
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
        val status = WsFrame.readLine(ins) ?: throw EOFException("no handshake response")
        val statusCode = status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        if (statusCode != 101) {
            throw IOException("handshake rejected: $status")
        }

        var acceptHeader: String? = null
        var upgradeOk = false
        var connectionOk = false
        var headerCount = 0
        var headerBytes = 0
        while (true) {
            val line = WsFrame.readLine(ins)
                ?: throw EOFException("handshake response truncated before end of headers")
            if (line.isEmpty()) break
            headerCount++
            headerBytes += line.length + 2
            if (headerCount > MAX_HANDSHAKE_HEADERS || headerBytes > MAX_HANDSHAKE_HEADER_BYTES) {
                throw IOException("handshake response headers too large")
            }
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (name) {
                    "sec-websocket-accept" -> acceptHeader = value
                    "upgrade" -> if (value.equals("websocket", ignoreCase = true)) upgradeOk = true
                    "connection" -> {
                        if (value.split(',').any { it.trim().equals("upgrade", ignoreCase = true) }) {
                            connectionOk = true
                        }
                    }
                }
            }
        }
        if (!upgradeOk || !connectionOk) {
            throw IOException("handshake rejected: missing Upgrade/Connection headers")
        }
        val expectedAccept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        if (acceptHeader != expectedAccept) {
            throw IOException("handshake rejected: bad or missing Sec-WebSocket-Accept")
        }

        val c = WsConn(sock, ins, out, "rws:$redactedUrl", WsConn.Role.BOTH, true, configId)
        val establishedAtNanos = System.nanoTime()
        conn = c
        connectedAt = System.currentTimeMillis()
        var added = false
        try {
            if (!running) return establishedAtNanos
            if (!Transport.add(c, transportEpoch)) return establishedAtNanos
            added = true
            c.loop { text -> server.onActionText(c, text) }
        } finally {
            c.close()
            if (added) Transport.remove(c)
            conn = null
            connectedAt = 0
        }
        return establishedAtNanos
    }

    private fun configureTls(ssl: SSLSocket, host: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configureTlsApi24(ssl, host)
        } else if (!isIpLiteral(host)) {
            try {
                ssl.javaClass.getMethod("setHostname", String::class.java).invoke(ssl, host)
            } catch (_: Throwable) {
            }
        }
    }

    private fun configureTlsApi24(ssl: SSLSocket, host: String) {
        val params = ssl.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        if (!isIpLiteral(host)) {
            try {
                params.serverNames = listOf(SNIHostName(host))
            } catch (_: IllegalArgumentException) {
            }
        }
        ssl.sslParameters = params
    }
}
