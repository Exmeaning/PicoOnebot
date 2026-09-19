package pico.onebot.net

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.PicoBoot
import pico.onebot.action.ActionRouter
import pico.onebot.action.SystemActions
import pico.onebot.core.Config
import pico.onebot.core.ContainerRestart
import pico.onebot.core.Counters
import pico.onebot.core.NetUtil
import pico.onebot.core.PicoLog
import pico.onebot.event.MetaEvents
import pico.onebot.kernel.ContactStore
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.PicoLoginManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * WebUI 后端(端口 6099 的 `/api/…`)。契约见 docs/design §5.1,前端 `web/src/lib/api.ts` 为准。
 *
 * 登录成功后签发进程内会话令牌；后续请求使用 `Authorization: Bearer <session>`。
 * 运维脚本仍可直接使用密码，浏览器不会把密码保存到 localStorage。
 * 日志流 `WS /api/logs/stream?token=` 走 query(在 [HttpServer] 里校验)。
 */
object WebApi {

    private const val MAX_EDIT_BYTES = 1024 * 1024L
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val secureRandom = SecureRandom()
    private val fileWriteLock = Any()

    class Response(val status: Int, val json: JSONObject)

    fun handle(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray
    ): Response {
        // 登录不需要先有票据
        if (path == "/api/auth/login" && method == "POST") return login(body)

        if (!authorized(headers, query)) {
            return Response(401, JSONObject().put("ok", false).put("message", "unauthorized"))
        }
        if (!Config.webuiPasswordInitialized && !(path == "/api/webui/token" && method == "PUT")) {
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
                path == "/api/webui/token" && method == "PUT" -> setToken(body)
                path == "/api/qr" && method == "GET" -> Response(200, qr(false))
                path == "/api/qr/refresh" && method == "POST" -> Response(200, qr(true))
                else -> Response(404, JSONObject().put("ok", false).put("message", "unknown route: $path"))
            }
        } catch (t: Throwable) {
            PicoLog.e("webapi $path failed", t)
            Response(500, JSONObject().put("ok", false).put("message", t.javaClass.simpleName + ": " + t.message))
        }
    }

    /** 前端把 JSON 数组直接反序列化,包一层给 HttpServer 用 `_array` 标记原样输出。 */
    private fun arrayResponse(arr: JSONArray): Response =
        Response(200, JSONObject().put("_array", arr))

    fun authorized(headers: Map<String, String>, query: Map<String, String>): Boolean {
        val auth = headers["authorization"] ?: ""
        val credential = if (auth.startsWith("Bearer ")) auth.substring(7) else auth
        if (credential.isNotEmpty() && (sessions.contains(credential) || Config.verifyWebuiPassword(credential))) return true
        val queryCredential = query["token"] ?: return false
        return queryCredential.isNotEmpty() &&
            (sessions.contains(queryCredential) || Config.verifyWebuiPassword(queryCredential))
    }

    private fun login(body: ByteArray): Response {
        val token = try {
            JSONObject(String(body, Charsets.UTF_8)).optString("token", "")
        } catch (t: Throwable) {
            ""
        }
        if (token.isNotEmpty() && sessions.contains(token)) {
            return loginResponse(token)
        }
        if (token.isNotEmpty() && Config.verifyWebuiPassword(token)) {
            val session = newSession()
            return loginResponse(session)
        }
        return Response(401, JSONObject().put("ok", false).put("message", "密码不正确或会话已失效"))
    }

    private fun loginResponse(session: String): Response =
        Response(
            200, JSONObject()
                .put("ok", true)
                .put("token", session)
                .put("mustChangePassword", !Config.webuiPasswordInitialized)
        )

    private fun newSession(): String {
        if (sessions.size >= 64) sessions.clear()
        val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val session = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        sessions.add(session)
        return session
    }

    private fun logout(headers: Map<String, String>, query: Map<String, String>): Response {
        val auth = headers["authorization"] ?: ""
        val credential = if (auth.startsWith("Bearer ")) auth.substring(7) else auth
        if (credential.isNotEmpty()) sessions.remove(credential)
        query["token"]?.let { sessions.remove(it) }
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
    } catch (t: Throwable) {
        ""
    }

    private fun hostQqVersion(): String = try {
        val ctx = mqq.app.MobileQQ.sMobileQQ
        if (ctx != null) ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" else ""
    } catch (t: Throwable) {
        ""
    }

    /**
     * ConnectionInfo[]:配置里的每个连接项 + 它此刻**真实**的运行状态。
     *
     * 这里绝不能拿 `enabled` 冒充 `connected` —— 配置写了不等于端口起来了,
     * 更不等于对端连上了。前端的在线灯就靠这份数据,报假的等于骗用户。
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

        val ws = Config.wsServers()
        for (i in 0 until ws.length()) {
            val o = ws.optJSONObject(i) ?: continue
            addListener(o, "ws-server", "正向 WS", "ws://" + o.optString("host") + ":" + o.optInt("port"))
        }
        val hs = Config.httpServers()
        for (i in 0 until hs.length()) {
            val o = hs.optJSONObject(i) ?: continue
            addListener(o, "http-server", "HTTP 服务", "http://" + o.optString("host") + ":" + o.optInt("port"))
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
            // HTTP 上报是"有事件才发"的单向通道,没有长连接可言:在队列里就算生效。
            val reporting = enabled && HttpReporter.reporting(url)
            add(id, "http-client", o.optString("name", "HTTP 上报"), url,
                enabled, reporting, 0, if (reporting) PicoBoot.networkAppliedAt else 0L,
                if (!enabled) "已停用" else if (reporting) "已生效 · 有事件即上报" else "未生效")
        }
        return out
    }

    private fun putConfig(body: ByteArray): Response = synchronized(fileWriteLock) {
        val patch = JSONObject(String(body, Charsets.UTF_8))
        val candidate = patch.optJSONObject("network") ?: Config.network()
        val errors = Config.validateNetwork(candidate)
        if (errors.isNotEmpty()) {
            return Response(
                400,
                JSONObject().put("ok", false).put("message", errors.joinToString("；"))
            )
        }
        val previous = Config.raw().toString()
        Config.applyAppConfig(patch)
        PicoLog.i("config updated via webui")
        return Response(200, applyRuntimeConfig(previous).put("ok", true))
    }

    /**
     * 保存后真正去动运行时,并把**实际发生了什么**原样回给前端 ——
     * 前端的提示语只许复述这里的数字,不许自己替后端宣布"已热重载"。
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
        if (!dir.exists()) return error(404, "目录不存在")
        if (!dir.isDirectory) return error(400, "目标不是目录")

        val entries = JSONArray()
        val children = dir.listFiles()?.sortedWith(
            compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })
        ) ?: emptyList()
        for (child in children) {
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
        val activeConfig = Config.activeConfigFile?.takeIf { it.isFile }?.let { config ->
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

    private fun readFile(query: Map<String, String>): Response {
        val target = resolveManagedPath(query["path"] ?: "")
            ?: return error(400, "文件路径无效")
        if (!target.isFile) return error(404, "文件不存在")
        if (!isEditableText(target)) {
            return error(415, "仅支持编辑不超过 1 MiB 的 UTF-8 文本文件")
        }
        return Response(
            200,
            JSONObject()
                .put("path", relativePath(target))
                .put("name", target.name)
                .put("content", target.readText(Charsets.UTF_8))
                .put("size", target.length())
                .put("modifiedAt", target.lastModified())
        )
    }

    private fun putFile(body: ByteArray): Response {
        val req = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Throwable) {
            return error(400, "请求内容不是合法 JSON")
        }
        val target = resolveManagedPath(req.optString("path", ""))
            ?: return error(400, "文件路径无效")
        if (!target.isFile) return error(404, "文件不存在")
        if (!isEditableText(target)) {
            return error(415, "仅支持编辑不超过 1 MiB 的 UTF-8 文本文件")
        }
        if (!req.has("content") || req.isNull("content")) return error(400, "缺少文件内容")
        val content = req.getString("content")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_EDIT_BYTES) return error(413, "文件内容不能超过 1 MiB")

        val activeConfig = try {
            Config.activeConfigFile?.canonicalFile == target.canonicalFile
        } catch (_: Throwable) {
            false
        }
        if (target.extension.equals("json", ignoreCase = true)) {
            try {
                if (activeConfig) JSONObject(content) else org.json.JSONTokener(content).nextValue()
            } catch (t: Throwable) {
                return error(400, "JSON 格式错误: " + t.message)
            }
        }

        val expectedModifiedAt = req.optLong("expectedModifiedAt", -1L)
        var runtime = JSONObject().put("restartRequired", false).put("warnings", JSONArray())
            .put("applied", JSONObject().put("reloaded", false).put("listeners", 0)
                .put("reverse", 0).put("reporters", 0).put("appliedAt", 0L))
        synchronized(fileWriteLock) {
            if (expectedModifiedAt >= 0 && target.lastModified() != expectedModifiedAt) {
                return error(409, "文件已被其他程序修改，请重新加载后再保存")
            }
            try {
                if (activeConfig) {
                    val previous = Config.raw().toString()
                    Config.replaceConfig(JSONObject(content))
                    runtime = applyRuntimeConfig(previous)
                } else {
                    target.writeBytes(bytes)
                }
            } catch (error: IllegalArgumentException) {
                return error(400, "配置无效: " + error.message)
            } catch (t: Throwable) {
                return error(500, "文件保存失败: " + t.message)
            }
        }

        PicoLog.i("file updated via webui: " + (relativePath(target) ?: target.name))
        return Response(
            200,
            JSONObject()
                .put("ok", true)
                .put("size", target.length())
                .put("modifiedAt", target.lastModified())
                .put("restartRequired", runtime.optBoolean("restartRequired"))
                .put("warnings", runtime.getJSONArray("warnings"))
                .put("applied", runtime.getJSONObject("applied"))
        )
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
        if (!file.isFile || file.length() > MAX_EDIT_BYTES) return false
        return try {
            val probe = file.inputStream().use { input ->
                val buffer = ByteArray(minOf(8192L, file.length()).toInt())
                val count = input.read(buffer)
                if (count <= 0) ByteArray(0) else buffer.copyOf(count)
            }
            if (probe.any { it == 0.toByte() }) return false
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(probe))
            true
        } catch (_: Throwable) {
            false
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
        } catch (error: Throwable) {
            PicoLog.e("QQ restart request failed", error)
            error(503, "无法提交重启请求，请检查容器守护服务")
        }
    }

    private fun accountLogout(): JSONObject {
        val r = ActionRouter.call("_pico_logout", JSONObject())
        return JSONObject().put("ok", r.optString("status") == "ok")
    }

    private fun setToken(body: ByteArray): Response {
        val token = JSONObject(String(body, Charsets.UTF_8)).optString("token", "").trim()
        if (token.length < 8) {
            return Response(400, JSONObject().put("ok", false).put("message", "控制台密码至少 8 位"))
        }
        if (token == Config.DEFAULT_WEBUI_PASSWORD) {
            return Response(400, JSONObject().put("ok", false).put("message", "不能继续使用初始密码"))
        }
        if (!Config.initializeWebuiPassword(token)) {
            return Response(409, JSONObject().put("ok", false).put("message", "控制台密码已设置，不允许再次修改"))
        }
        PicoLog.i("webui password initialized")
        return Response(200, JSONObject().put("ok", true))
    }

    /** 二维码 TTL(秒),与 PicoLoginManager.info() 的过期判定一致。 */
    private const val QR_TTL_SEC = 85

    /**
     * `GET /api/qr` 只读不触发刷新(前端 2s 轮询,不能每次都向登录服务器要新码);
     * `POST /api/qr/refresh` 才主动要一张(在 LoginWithStateFragment 上则先点"切换账号"),最多等 3s。
     * 形状见设计 §4.3:`{status: online|ok|expired|waiting_qr, url, image, ageSeconds, expiresIn, scanned, hasFragment, hasStateFragment}`。
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
            val before = PicoLoginManager.latestQrTime
            if (PicoLoginManager.refreshQr()) {
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 3000 && PicoLoginManager.latestQrTime <= before) {
                    try {
                        Thread.sleep(100)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
        val info = PicoLoginManager.info()
        val url = info.optString("url", "")
        val age = info.optInt("age_seconds", -1)
        val expired = info.optBoolean("expired", false)
        val status = when {
            url.isEmpty() -> "waiting_qr"
            expired -> "expired"
            else -> "ok"
        }
        val bytes = PicoLoginManager.latestQrBytes
        val image = if (status == "ok" && bytes != null)
            "data:" + imageMime(bytes) + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        else ""
        return out.put("status", status).put("online", false)
            .put("url", url).put("image", image)
            .put("ageSeconds", if (age < 0) 0 else age)
            .put("expiresIn", if (status == "ok") (QR_TTL_SEC - age).coerceAtLeast(0) else 0)
            .put("scanned", info.optBoolean("scanned", false))
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
