package pico.onebot

import android.content.Context
import pico.onebot.action.MsgActions
import pico.onebot.action.SystemActions
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.event.EventBus
import pico.onebot.event.MetaEvents
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.MsgPipeline
import pico.onebot.kernel.QimeiCompat
import pico.onebot.kernel.UinUidStore
import pico.onebot.msg.MsgIdStore
import pico.onebot.net.HttpServer
import pico.onebot.net.WsClient
import java.io.File

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

    @Volatile
    private var started = false

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var webuiServer: HttpServer? = null

    private val onebotServers = ArrayList<HttpServer>()
    private val reverseClients = ArrayList<WsClient>()

    /** 配置项 id -> 真正起来的监听 / 反向客户端 / 绑定失败原因。WebUI 的状态只看这三张表。 */
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
     * 当前真正在 listening 的 OneBot 端口与它的 token。
     *
     * 媒体外链必须指向一个**真实有服务**的端口:老实现用 `Config.port`(第一个启用的
     * 正向 WS 配置项),纯反向 WS / 纯 HTTP 上报部署下那个端口根本没人监听,上游取图
     * 直接 connection refused。
     *
     * 选择顺序:**非回环监听优先**。两个监听项并存时(例如 `127.0.0.1:3001` 无 token
     * + `0.0.0.0:3002` 有 token),先前按插入序取第一个会得到只在回环上可达的端口,
     * 拼出的 `http://<LAN_IP>:3001/...` 从别的机器根本连不上。
     *
     * @return (host, port, token);没有任何 OneBot 监听时返回 null。
     */
    fun activeOnebotEndpoint(): Triple<String, Int, String>? = synchronized(this) {
        var loopback: Triple<String, Int, String>? = null
        for ((id, server) in listenerById) {
            if (!server.running) continue
            val token = Config.wsServers().let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
            }?.optString("token", "")
                ?: Config.httpServers().let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.firstOrNull { it.optString("id") == id }
                }?.optString("token", "")
                ?: ""
            val candidate = Triple(server.host, server.port, token)
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
        if (started) return
        QimeiCompat.installSignatureCompat(ctx)
        val proc = currentProcessName()
        if (proc != null && proc != ctx.packageName) {
            if (proc.endsWith(":MSF")) QimeiCompat.startMsf(ctx)
            // 子进程(:MSF / :P_OPT)不启动任何东西
            return
        }
        started = true
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
        if (onebotServers.isNotEmpty()) KernelGate.setState(KernelGate.State.TRANSPORT_UP)

        // WebUI 独立监听(6099),静态页 + /api/*
        try {
            val web = HttpServer(Config.webuiHost, Config.webuiPort, HttpServer.Role.WEBUI)
            web.start()
            webuiServer = web
            PicoLog.i("WebUI 控制台: http://" + Config.webuiHost + ":" + Config.webuiPort + "/  password=" +
                (if (!Config.webuiPasswordInitialized) Config.DEFAULT_WEBUI_PASSWORD + " (初始,首次进入必须修改)" else "已设置"))
        } catch (t: Throwable) {
            PicoLog.e("webui server bind failed on " + Config.webuiHost + ":" + Config.webuiPort, t)
        }

        PicoLog.i("WebUI 局域网地址: " + pico.onebot.core.NetUtil.webuiUrl())

        MetaEvents.start()

        // 延迟首探:别在宿主自己 init 内核之前去碰 KernelServiceUtil 的静态初始化;
        // 旧版默认 5000ms 会拉长启动/自动登录时的监听盲窗,默认(含旧默认值 5000)收敛到 1000ms
        val configuredDelay = Config.long("kernel_probe_delay_ms", 1000L)
        val delay = (if (configuredDelay == 5000L) 1000L else configuredDelay).coerceAtLeast(0L)
        if (delay > 0L) {
            try {
                Thread.sleep(delay)
            } catch (t: InterruptedException) {
                return
            }
        }
        KernelGate.startWatch()

        Runtime.getRuntime().addShutdownHook(Thread {
            MsgIdStore.save()
            UinUidStore.save()
        })

        PicoLog.i("PicoOnebot ready: ${onebotServers.size} server(s), ${reverseClients.size} reverse WS client(s)")
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
            val host = o.optString("host", "0.0.0.0")
            val port = o.optInt("port", 3001)
            val token = o.optString("token", "")
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
                allowCors = o.optBoolean("cors", true),
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
            val host = o.optString("host", "0.0.0.0")
            val port = o.optInt("port", 0)
            if (port <= 0) {
                bindErrorById[id] = "端口无效"
                continue
            }
            val token = o.optString("token", "")
            if (!Config.isLoopbackHost(host) && token.isBlank()) {
                errors.add("HTTP $host:$port: 非回环监听必须配置 Access Token(已拒绝监听)")
                bindErrorById[id] = "缺少 Access Token,已拒绝监听"
                PicoLog.e("http server refused on $host:$port: missing access token")
                continue
            }
            // httpServers 项:cors / websocket 两个开关在配置里存在,必须真的生效
            val hs = HttpServer(
                host, port, HttpServer.Role.ONEBOT, token, id,
                allowCors = o.optBoolean("cors", true),
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
            val url = o.optString("url", "")
            val c = WsClient(url, o.optString("token", ""), actionDispatcher, o.optLong("reconnectInterval", 3000), id)
            reverseClients.add(c)
            reverseById[id] = c
            c.start()
            PicoLog.i("reverse ws target: $url")
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

    /** /proc/self/cmdline 是判断进程名最不依赖框架的办法。 */
    fun currentProcessName(): String? = try {
        val raw = File("/proc/self/cmdline").readBytes()
        val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
        String(raw, 0, end, Charsets.UTF_8).trim()
    } catch (t: Throwable) {
        null
    }
}
