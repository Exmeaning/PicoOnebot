package pico.onebot

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import pico.onebot.action.MsgActions
import pico.onebot.action.SystemActions
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.event.EventBus
import pico.onebot.event.MetaEvents
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.MsgPipeline
import pico.onebot.kernel.MsgWatchdog
import pico.onebot.kernel.QimeiCompat
import pico.onebot.kernel.ReadReporter
import pico.onebot.kernel.UinUidStore
import pico.onebot.msg.MsgIdStore
import pico.onebot.net.HttpServer
import pico.onebot.net.WsClient
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动编排。被 [pico.onebot.hook.PicoAppHook] 从宿主 Application 里调一次。
 *
 * 纪律:**任何异常都不许冒泡回宿主**(我们是寄生在真 QQ 里的,崩了就是把 QQ 崩了),
 * 并且**只在主进程工作**(注入代码会在 :MSF / :P_OPT 里各跑一遍)。
 */
object PicoBoot {

    val VERSION: String = BuildConfig.PICO_VERSION
    val GIT_SHA: String = BuildConfig.PICO_GIT_SHA
    val REPO: String = BuildConfig.PICO_REPO

    @Volatile
    var startedAt: Long = 0
        private set

    private val started = AtomicBoolean(false)

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var webuiServer: HttpServer? = null

    private val onebotServers = ArrayList<HttpServer>()
    private val reverseClients = ArrayList<WsClient>()

    /** 配置项 id -> 真正起来的监听 / 反向客户端 / 绑定失败原因。WebUI 的状态只看这几张表。 */
    private val listenerById = LinkedHashMap<String, HttpServer>()
    private val reverseById = LinkedHashMap<String, WsClient>()
    private val bindErrorById = LinkedHashMap<String, String>()

    /** 最近一次 [reloadNetwork] 真正执行完的时刻。 */
    @Volatile
    var networkAppliedAt: Long = 0
        private set

    /** 这个配置项此刻是否真的在监听。 */
    fun listening(id: String): Boolean = synchronized(this) { listenerById[id]?.running == true }

    /** 这个配置项此刻是否真的连着对端。 */
    fun reverseConnected(id: String): Boolean = synchronized(this) { reverseById[id]?.connected == true }

    /** 这个配置项是否已经起了重连线程(连上与否另说)。 */
    fun reverseRunning(id: String): Boolean = synchronized(this) { reverseById.containsKey(id) }

    /** 这条反向连接建立的时刻;没连上是 0。 */
    fun reverseSince(id: String): Long = synchronized(this) { reverseById[id]?.connectedAt ?: 0L }

    /** 上一次重载时这个配置项的失败原因,没有就是 null。 */
    fun bindError(id: String): String? = synchronized(this) { bindErrorById[id] }

    /**
     * 当前真正在 listening 的 OneBot 监听地址与端口(优先非回环监听)。
     *
     * @return (host, port);没有任何 OneBot 监听时返回 null。
     */
    fun activeOnebotEndpoint(): Pair<String, Int>? = synchronized(this) {
        var loopback: Pair<String, Int>? = null
        for ((_, server) in listenerById) {
            if (!server.running) continue
            val candidate = Pair(server.host, server.port)
            if (!Config.isLoopbackHost(server.host)) return@synchronized candidate
            if (loopback == null) loopback = candidate
        }
        loopback
    }

    /** 媒体外链是否真的可用:要么配了 media_base_url,要么有在听的 OneBot 端口。 */
    fun mediaUrlAvailable(): Boolean =
        Config.str("media_base_url", "").isNotEmpty() || activeOnebotEndpoint() != null

    /** [reloadNetwork] 的结果:如实告诉调用方到底起来了什么。 */
    class ReloadResult(
        val errors: List<String>,
        val listeners: Int,
        val reverse: Int,
        val reporters: Int
    )

    fun start(ctx: Context) {
        if (started.get()) return
        QimeiCompat.installSignatureCompat(ctx)
        val proc = currentProcessName()
        if (proc.isNullOrBlank()) {
            // fail-closed: 无法确认进程名时绝不在未知子进程中拉起主栈
            Log.w(PicoLog.TAG, "PicoBoot: unable to determine process name, skipping boot")
            return
        }
        if (proc != ctx.packageName) {
            if (proc.endsWith(":MSF")) QimeiCompat.startMsf(ctx)
            // 子进程(:MSF / :P_OPT)不启动任何东西
            return
        }
        if (!started.compareAndSet(false, true)) return
        startedAt = System.currentTimeMillis()
        Thread({ bootSafely(ctx) }, "pico-boot").apply { isDaemon = true }.start()
    }

    private fun bootSafely(ctx: Context) {
        try {
            boot(ctx)
        } catch (t: Throwable) {
            PicoLog.e("boot failed", t)
        }
    }

    private fun boot(ctx: Context) {
        Config.load(ctx)
        if (!Config.enabled) {
            PicoLog.i("PicoOnebot disabled by config")
            return
        }
        PicoLog.i("PicoOnebot $VERSION booting, process=" + (currentProcessName() ?: "?"))

        MsgIdStore.load()
        UinUidStore.load()

        EventBus.start()
        MsgPipeline.start()
        ReadReporter.start()
        MsgWatchdog.start()

        SystemActions.register()
        MsgActions.register()
        pico.onebot.action.MediaActions.register()
        pico.onebot.action.ForwardActions.register()
        pico.onebot.action.InfoActions.register()
        PicoLog.i("actions registered: " + pico.onebot.action.ActionRouter.names().size)

        for (warning in Config.validateNetwork()) {
            PicoLog.w("unsafe network config loaded from file: $warning")
        }

        reloadNetwork()
        if (synchronized(this) { onebotServers.isNotEmpty() }) {
            KernelGate.setState(KernelGate.State.TRANSPORT_UP)
        }

        // WebUI 独立监听(6099),静态页 + /api/*
        val webuiBindHost = if (Config.corruptRecoveryMode) "127.0.0.1" else Config.webuiHost
        try {
            val web = HttpServer(
                webuiBindHost,
                Config.webuiPort,
                HttpServer.Role.WEBUI,
                corsAllowedOriginsProvider = {
                    if (Config.corruptRecoveryMode) emptyList() else Config.webuiCorsAllowedOrigins
                }
            )
            web.start()
            webuiServer = web
            if (Config.corruptRecoveryMode) {
                PicoLog.w("WebUI 控制台(损坏配置恢复模式): http://127.0.0.1:" + Config.webuiPort + "/  仅限本机访问，请使用本地日志中的一次性恢复令牌登录并修复 config.json")
            } else {
                PicoLog.i("WebUI 控制台: http://" + webuiBindHost + ":" + Config.webuiPort + "/  password=" +
                    (if (!Config.webuiPasswordInitialized) Config.DEFAULT_WEBUI_PASSWORD + " (初始,首次进入必须修改)" else "已设置"))
            }
        } catch (t: Throwable) {
            PicoLog.e("webui server bind failed on " + webuiBindHost + ":" + Config.webuiPort, t)
        }

        PicoLog.i("WebUI 局域网地址: " + pico.onebot.core.NetUtil.webuiUrl())

        MetaEvents.start()

        // 延迟首探:别在宿主自己 init 内核之前去碰 KernelServiceUtil 的静态初始化; 默认 1000ms,如实尊重用户显式配置
        val delay = Config.long("kernel_probe_delay_ms", 1000L).coerceAtLeast(0L)
        if (delay > 0L) {
            try {
                Thread.sleep(delay)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        KernelGate.startWatch()

        Runtime.getRuntime().addShutdownHook(Thread {
            MsgIdStore.save()
            UinUidStore.save()
        })

        val (srvCount, revCount) = synchronized(this) { onebotServers.size to reverseClients.size }
        PicoLog.i("PicoOnebot ready: $srvCount server(s), $revCount reverse WS client(s)")
    }

    @Synchronized
    fun reloadNetwork(): ReloadResult {
        reverseClients.forEach { it.stop() }
        reverseClients.clear()
        onebotServers.forEach { it.stop() }
        onebotServers.clear()
        listenerById.clear()
        reverseById.clear()
        bindErrorById.clear()
        pico.onebot.net.Transport.closeAll()
        val errors = ArrayList<String>()
        // 正向 WS 监听:每个 enabled 的 wsServers 项各起一个(各自的端口与口令)
        val servers = Config.wsServers()
        for (i in 0 until servers.length()) {
            val o = servers.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val id = o.optString("id")
            val host = if (o.isNull("host")) "0.0.0.0" else o.optString("host", "0.0.0.0").trim()
            val port = if (o.isNull("port")) -1 else o.optInt("port", 3001)
            if (host.isEmpty()) {
                errors.add("正向 WS $id: 监听地址不能为空")
                bindErrorById[id] = "监听地址不能为空"
                continue
            }
            if (port !in 1..65535) {
                errors.add("正向 WS $host:$port: 端口必须在 1-65535 之间")
                bindErrorById[id] = "端口无效"
                continue
            }
            val token = if (o.isNull("token")) "" else o.optString("token", "")
            // 非回环 + 空 token 意味着任何人都能调 /api/pico 改配置、读 token、
            // 触发 logout。这里必须拒绝监听,而不是像以前那样只打一条 warn 就照常起。
            if (!Config.isLoopbackHost(host) && token.isBlank()) {
                errors.add("正向 WS $host:$port: 非回环监听必须配置 Access Token(已拒绝监听)")
                bindErrorById[id] = "缺少 Access Token,已拒绝监听"
                PicoLog.e("onebot server refused on $host:$port: missing access token")
                continue
            }
            val hs = HttpServer(
                host, port, HttpServer.Role.ONEBOT, token, id,
                allowCors = o.optBoolean("cors", false),
                allowWebSocket = o.optBoolean("websocket", true)
            )
            try {
                hs.start()
                onebotServers.add(hs)
                listenerById[id] = hs
            } catch (t: Throwable) {
                errors.add("正向 WS $host:$port: ${t.message}")
                bindErrorById[id] = t.message ?: t.javaClass.simpleName
                PicoLog.e("onebot server bind failed on $host:$port", t)
            }
        }
        // 纯 HTTP 服务端项(允许再开独立端口),同一 HttpServer 类即可承载
        val httpServers = Config.httpServers()
        for (i in 0 until httpServers.length()) {
            val o = httpServers.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val id = o.optString("id")
            val host = if (o.isNull("host")) "0.0.0.0" else o.optString("host", "0.0.0.0").trim()
            val port = if (o.isNull("port")) -1 else o.optInt("port", 0)
            if (host.isEmpty()) {
                errors.add("HTTP $id: 监听地址不能为空")
                bindErrorById[id] = "监听地址不能为空"
                continue
            }
            if (port !in 1..65535) {
                errors.add("HTTP $host:$port: 端口必须在 1-65535 之间")
                bindErrorById[id] = "端口无效"
                continue
            }
            val token = if (o.isNull("token")) "" else o.optString("token", "")
            if (!Config.isLoopbackHost(host) && token.isBlank()) {
                errors.add("HTTP $host:$port: 非回环监听必须配置 Access Token(已拒绝监听)")
                bindErrorById[id] = "缺少 Access Token,已拒绝监听"
                PicoLog.e("http server refused on $host:$port: missing access token")
                continue
            }
            // httpServers 项:cors / websocket 两个开关在配置里存在,必须真的生效
            val hs = HttpServer(
                host, port, HttpServer.Role.ONEBOT, token, id,
                allowCors = o.optBoolean("cors", false),
                allowWebSocket = o.optBoolean("websocket", false)
            )
            try {
                hs.start()
                onebotServers.add(hs)
                listenerById[id] = hs
            } catch (t: Throwable) {
                errors.add("HTTP $host:$port: ${t.message}")
                bindErrorById[id] = t.message ?: t.javaClass.simpleName
                PicoLog.e("http server bind failed on $host:$port", t)
            }
        }
        server = onebotServers.firstOrNull()

        val actionDispatcher = server ?: HttpServer("127.0.0.1", 0, HttpServer.Role.ONEBOT)
        val clients = Config.wsClients()
        for (i in 0 until clients.length()) {
            val o = clients.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val id = o.optString("id")
            val url = if (o.isNull("url")) "" else o.optString("url", "").trim()
            if (url.isEmpty()) {
                errors.add("反向 WS $id: 目标地址不能为空")
                bindErrorById[id] = "目标地址为空"
                continue
            }
            val token = if (o.isNull("token")) "" else o.optString("token", "")
            val c = WsClient(url, token, actionDispatcher, o.optLong("reconnectInterval", 3000), id)
            reverseClients.add(c)
            reverseById[id] = c
            c.start()
            PicoLog.i("reverse ws target: " + WsClient.safeUrl(url))
        }

        pico.onebot.net.HttpReporter.start()
        networkAppliedAt = System.currentTimeMillis()
        val reporters = pico.onebot.net.HttpReporter.targetCount()
        PicoLog.i(
            "network applied: " + onebotServers.size + " listener(s), " +
                reverseClients.size + " reverse WS client(s), " + reporters + " http report target(s)"
        )
        return ReloadResult(errors, onebotServers.size, reverseClients.size, reporters)
    }

    /** 优先读 /proc/self/cmdline,失败时回退至 Application.getProcessName() (API 28+) 与 ActivityThread。 */
    fun currentProcessName(): String? {
        try {
            val raw = File("/proc/self/cmdline").readBytes()
            val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
            val name = String(raw, 0, end, Charsets.UTF_8).trim()
            if (name.isNotEmpty()) return name
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val name = Application.getProcessName()
                if (!name.isNullOrBlank()) return name.trim()
            } catch (_: Throwable) {
            }
        }
        return try {
            val cls = Class.forName("android.app.ActivityThread")
            val name = cls.getDeclaredMethod("currentProcessName").invoke(null) as? String
            name?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }
}
