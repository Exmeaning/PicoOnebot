package pico.onebot.net

import android.util.Base64
import org.json.JSONObject
import pico.onebot.action.ActionRouter
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.proto.Ob
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * 一个端口同时承载:正向 WebSocket(`/`、`/api`、`/event`)、
 * HTTP API(`POST /{action}`、`GET /{action}?a=b`)、控制台静态文件与 `/api/pico/…`。
 *
 * 线程模型:一个 accept 线程 + 每连接一条线程(连接数量级是个位数,够用且没有依赖)。
 */
class HttpServer(
    val host: String,
    val port: Int,
    val role: Role = Role.ONEBOT,
    /** ONEBOT 角色的鉴权口令；WEBUI 角色使用 Config 中的密码派生值校验。 */
    private val serverToken: String = "",
    /** 起这个监听的网络配置项 id,接进来的连接都挂在它名下。 */
    val configId: String = ""
) {

    enum class Role { ONEBOT, WEBUI }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var running = false
        private set

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName(host), port))
        server = s
        running = true
        Thread({ acceptLoop(s) }, "pico-http-accept").apply { isDaemon = true }.start()
        PicoLog.i(role.name.lowercase() + " http/ws listening on $host:$port")
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (ignored: Throwable) {
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val sock = try {
                s.accept()
            } catch (t: Throwable) {
                if (running) PicoLog.w("accept failed: " + t.message)
                continue
            }
            Thread({ handle(sock) }, "pico-conn-" + sock.port).apply { isDaemon = true }.start()
        }
    }

    private fun handle(sock: Socket) {
        // WS 升级成功后连接的生命周期交给 WsConn;在那之前的任何一条 return 路径
        // (空请求行 / 畸形请求行 / 401)都必须自己把 socket 关掉,否则连接泄漏。
        var handedOff = false
        try {
            sock.tcpNoDelay = true
            val ins = BufferedInputStream(sock.getInputStream(), 8192)
            val out = sock.getOutputStream()

            val requestLine = WsFrame.readLine(ins) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = WsFrame.readLine(ins) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val query = if (qIdx >= 0) parseQuery(rawPath.substring(qIdx + 1)) else HashMap()

            val upgrade = headers["upgrade"]?.lowercase()
            if (upgrade == "websocket") {
                if (role == Role.WEBUI) {
                    if (path.trimEnd('/') != "/api/logs/stream" || !WebApi.authorized(headers, query)) {
                        writeText(out, 401, "text/plain", "unauthorized")
                        sock.close()
                        return
                    }
                    handedOff = true
                    serveLogStream(sock, ins, out, headers)
                    return
                }
                if (!authorized(headers, query)) {
                    writeText(out, 401, "text/plain", "unauthorized")
                    sock.close()
                    return
                }
                handedOff = true
                serveWebSocket(sock, ins, out, path, headers)
                return
            }

            val body = readBody(ins, headers)
            if (role == Role.WEBUI) {
                serveWebUi(sock, out, method, path, query, headers, body)
            } else {
                serveHttp(sock, out, method, path, query, headers, body)
            }
        } catch (t: Throwable) {
            PicoLog.d("conn error: " + t.javaClass.simpleName + " " + t.message)
        } finally {
            // serveHttp 自己会关;这里兜住所有没走到 serveHttp 的提前返回。
            if (!handedOff) {
                try {
                    sock.close()
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    // ---------------- WebSocket ----------------

    /** 写 101 Switching Protocols;缺 key 时写 400 关连接并返回 false。 */
    private fun wsHandshake(sock: Socket, out: OutputStream, headers: Map<String, String>): Boolean {
        val key = headers["sec-websocket-key"]
        if (key == null) {
            writeText(out, 400, "text/plain", "missing Sec-WebSocket-Key")
            sock.close()
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

    /** WebUI 的实时日志流:握手后订阅 PicoLog,每条新日志推一帧,直到对端断开。 */
    private fun serveLogStream(sock: Socket, ins: InputStream, out: OutputStream, headers: Map<String, String>) {
        if (!wsHandshake(sock, out, headers)) return
        val conn = WsConn(sock, ins, out, sock.inetAddress.hostAddress + ":" + sock.port, WsConn.Role.EVENT, false)
        // 先补发最近若干条,前端一连上就有内容
        for (e in PicoLog.entries(200)) conn.sendText(e.toJson().toString())
        val unsub = PicoLog.subscribe { entry -> conn.sendText(entry.toJson().toString()) }
        try {
            conn.loop { /* 日志流是单向推送,忽略入站文本 */ }
        } finally {
            unsub()
            conn.close()
        }
    }

    /** WebUI 端口的 HTTP:`/api/…` 交给 [WebApi],其余当静态页(内嵌 WebUI)。 */
    private fun serveWebUi(
        sock: Socket,
        out: OutputStream,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        try {
            if (path.startsWith("/api/")) {
                val r = WebApi.handle(method, path, query, headers, body)
                val text = if (r.json.has("_array")) r.json.getJSONArray("_array").toString() else r.json.toString()
                writeText(out, r.status, "application/json", text)
                return
            }
            if (method == "GET" && serveStatic(out, path)) return
            writeText(out, 404, "text/plain", "not found: $path")
        } finally {
            try {
                sock.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun serveWebSocket(
        sock: Socket,
        ins: InputStream,
        out: OutputStream,
        path: String,
        headers: Map<String, String>
    ) {
        if (!wsHandshake(sock, out, headers)) return

        val role = when (path.trimEnd('/')) {
            "/api" -> WsConn.Role.API
            "/event" -> WsConn.Role.EVENT
            else -> WsConn.Role.BOTH
        }
        val conn = WsConn(sock, ins, out, sock.inetAddress.hostAddress + ":" + sock.port, role, false, configId)
        Transport.add(conn)
        try {
            conn.loop { text -> onActionText(conn, text) }
        } finally {
            Transport.remove(conn)
        }
    }

    /** 收到一条动作请求文本(正向 WS / 反向 WS 共用)。 */
    fun onActionText(conn: WsConn, text: String) {
        if (!conn.wantsActions) return
        val reply = handleActionJson(text)
        if (reply != null) conn.sendText(reply.toString())
    }

    // ---------------- HTTP ----------------

    private fun serveHttp(
        sock: Socket,
        out: OutputStream,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        try {
            if (path.startsWith("/api/pico")) {
                if (!authorized(headers, query)) {
                    writeText(out, 401, "application/json", Ob.failed(1403, "unauthorized").toString())
                    return
                }
                val resp = AdminApi.handle(path.removePrefix("/api/pico"), query, body)
                writeText(out, 200, "application/json", resp.toString())
                return
            }

            // 落地媒体的字节流。上游框架多半在另一台机器上,给它安卓内部路径是没用的。
            if (path.startsWith("/media/")) {
                if (!authorized(headers, query)) {
                    writeText(out, 401, "application/json", Ob.failed(1403, "unauthorized").toString())
                    return
                }
                val f = pico.onebot.media.MediaFiles.lookup(path.removePrefix("/media/"))
                if (f == null) {
                    writeText(out, 404, "application/json", Ob.failed(1404, "media not found").toString())
                } else {
                    writeFile(out, f)
                }
                return
            }

            val action = path.trim('/')
            if (action.isNotEmpty() && ActionRouter.has(action)) {
                if (!authorized(headers, query)) {
                    writeText(out, 401, "application/json", Ob.failed(1403, "unauthorized").toString())
                    return
                }
                val params = when {
                    body.isNotEmpty() && (headers["content-type"] ?: "").contains("json") ->
                        JSONObject(String(body, Charsets.UTF_8))
                    body.isNotEmpty() -> mapToJson(parseQuery(String(body, Charsets.UTF_8)))
                    else -> mapToJson(query)
                }
                val resp = ActionRouter.call(action, params)
                writeText(out, 200, "application/json", resp.toString())
                return
            }

            if (method == "GET" && serveStatic(out, path)) return

            writeText(out, 404, "application/json", Ob.failed(1404, "unknown action or path: $path").toString())
        } finally {
            try {
                sock.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 控制台静态文件。顺序(见 docs/design §3.3):
     * `filesDir/pico-onebot/webui/`(开发覆盖) → `assets/pico/webui.html`(编译期内嵌单文件)。
     */
    private fun serveStatic(out: OutputStream, path: String): Boolean {
        val rel = if (path == "/" || path.isEmpty()) "index.html" else path.trimStart('/')
        val dir = Config.webuiDir
        if (dir.isDirectory) {
            val f = File(dir, rel)
            if (f.canonicalPath.startsWith(dir.canonicalPath) && f.isFile) {
                writeBytes(out, 200, mimeOfStatic(rel), f.readBytes())
                return true
            }
        }
        // 内嵌单文件:任何 html/根路径都回它(WebUI 是 hash 路由的单文件)
        if (rel == "index.html" || rel.endsWith(".html")) {
            val bytes = readEmbeddedWebui()
            if (bytes != null) {
                writeBytes(out, 200, "text/html; charset=utf-8", bytes)
                return true
            }
        }
        return false
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

    private fun readEmbeddedWebui(): ByteArray? = try {
        val ctx = mqq.app.MobileQQ.sMobileQQ
        ctx?.assets?.open("pico/webui.html")?.use { it.readBytes() }
    } catch (t: Throwable) {
        null
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

    private fun authorized(headers: Map<String, String>, query: Map<String, String>): Boolean {
        val token = serverToken
        if (token.isEmpty()) return true
        val auth = headers["authorization"] ?: ""
        if (auth.startsWith("Bearer ") && auth.substring(7) == token) return true
        if (auth == token) return true
        return query["access_token"] == token
    }

    private fun readBody(ins: InputStream, headers: Map<String, String>): ByteArray {
        val len = headers["content-length"]?.toIntOrNull() ?: return ByteArray(0)
        if (len <= 0 || len > 16 * 1024 * 1024) return ByteArray(0)
        val buf = ByteArray(len)
        WsFrame.readFully(ins, buf)
        return buf
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
    } catch (t: Throwable) {
        s
    }

    private fun mapToJson(map: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in map) if (k != "access_token") o.put(k, v)
        return o
    }

    private fun writeText(out: OutputStream, code: Int, type: String, body: String) =
        writeBytes(out, code, type, body.toByteArray(Charsets.UTF_8))

    /** 媒体可能有几十兆,**流式**写出去,不整个读进内存(安卓堆只有几百兆)。 */
    private fun writeFile(out: OutputStream, f: File) {
        try {
            val head = StringBuilder()
                .append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ").append(mimeOf(f.name)).append("\r\n")
                .append("Content-Length: ").append(f.length()).append("\r\n")
                .append("Content-Disposition: inline; filename=\"").append(f.name).append("\"\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Connection: close\r\n\r\n")
                .toString()
            out.write(head.toByteArray(Charsets.UTF_8))
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            out.flush()
        } catch (t: Throwable) {
            PicoLog.d("media write failed: " + t.message)
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
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

    private fun writeBytes(out: OutputStream, code: Int, type: String, body: ByteArray) {
        try {
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(code).append(if (code == 200) " OK" else " ERR").append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.size).append("\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
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
