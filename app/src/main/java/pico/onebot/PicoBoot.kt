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

        // 延迟首探:别在宿主自己 init 内核之前去碰 KernelServiceUtil 的静态初始化
        val delay = Config.long("kernel_probe_delay_ms", 5000)
        try {
            Thread.sleep(delay)
        } catch (t: InterruptedException) {
            return
        }
        KernelGate.startWatch()

        Runtime.getRuntime().addShutdownHook(Thread {
            MsgIdStore.save()
            UinUidStore.save()
        })

        PicoLog.i("PicoOnebot ready: ${onebotServers.size} server(s), ${reverseClients.size} reverse WS client(s)")
    }

    @Synchronized
    fun reloadNetwork(): List<String> {
        reverseClients.forEach { it.stop() }
        reverseClients.clear()
        onebotServers.forEach { it.stop() }
        onebotServers.clear()
        pico.onebot.net.Transport.closeAll()
        val errors = ArrayList<String>()
        // 正向 WS 监听:每个 enabled 的 wsServers 项各起一个(各自的端口与口令)
        val servers = Config.wsServers()
        for (i in 0 until servers.length()) {
            val o = servers.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val host = o.optString("host", "0.0.0.0")
            val port = o.optInt("port", 3001)
            val token = o.optString("token", "")
            val hs = HttpServer(host, port, HttpServer.Role.ONEBOT, token)
            try {
                hs.start()
                onebotServers.add(hs)
            } catch (t: Throwable) {
                errors.add("正向 WS $host:$port: ${t.message}")
                PicoLog.e("onebot server bind failed on $host:$port", t)
            }
        }
        // 纯 HTTP 服务端项(允许再开独立端口),同一 HttpServer 类即可承载
        val httpServers = Config.httpServers()
        for (i in 0 until httpServers.length()) {
            val o = httpServers.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val host = o.optString("host", "0.0.0.0")
            val port = o.optInt("port", 0)
            if (port <= 0) continue
            val token = o.optString("token", "")
            val hs = HttpServer(host, port, HttpServer.Role.ONEBOT, token)
            try {
                hs.start()
                onebotServers.add(hs)
            } catch (t: Throwable) {
                errors.add("HTTP $host:$port: ${t.message}")
                PicoLog.e("http server bind failed on $host:$port", t)
            }
        }
        server = onebotServers.firstOrNull()

        val actionDispatcher = server ?: HttpServer("127.0.0.1", 0, HttpServer.Role.ONEBOT)
        val clients = Config.wsClients()
        for (i in 0 until clients.length()) {
            val o = clients.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val url = o.optString("url", "")
            val c = WsClient(url, o.optString("token", ""), actionDispatcher, o.optLong("reconnectInterval", 3000))
            reverseClients.add(c)
            c.start()
            PicoLog.i("reverse ws target: $url")
        }

        pico.onebot.net.HttpReporter.start()
        PicoLog.i("network applied: " + onebotServers.size + " server(s), " + reverseClients.size + " reverse WS client(s)")
        return errors
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
