package pico.onebot.action

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.PicoBoot
import pico.onebot.core.Config
import pico.onebot.core.ContainerRestart
import pico.onebot.core.Counters
import pico.onebot.core.PicoLog
import pico.onebot.event.EventBus
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.MsgListenerProxy
import pico.onebot.kernel.MsgPipeline
import pico.onebot.kernel.MsgWatchdog
import pico.onebot.kernel.PicoLoginManager
import pico.onebot.kernel.ReadReporter
import pico.onebot.kernel.SendTracker
import pico.onebot.kernel.UinUidStore
import pico.onebot.msg.MsgIdStore
import pico.onebot.net.Transport
import pico.onebot.proto.Ob
import java.util.concurrent.atomic.AtomicBoolean

/** 不依赖登录就能回答的动作 —— M1 的验收面。 */
object SystemActions {

    private val delayedRestartPending = AtomicBoolean(false)

    fun register() {
        ActionRouter.register("get_version_info") {
            Ob.ok(
                JSONObject()
                    .put("app_name", "PicoOnebot")
                    .put("app_version", PicoBoot.VERSION)
                    .put("app_full_name", "PicoOnebot/" + PicoBoot.VERSION + " (watch-qqnt)")
                    .put("protocol_version", "v11")
                    .put("nt_protocol", "watch")
            )
        }

        ActionRouter.register("get_status") { Ob.ok(statusObject()) }

        ActionRouter.register("get_login_info") { loginInfo() }
        ActionRouter.register("get_self_info") { loginInfo() }

        // M4 起图片/语音都是真的能发了,继续回 false 会让上游主动降级成纯文本
        ActionRouter.register("can_send_image") { Ob.ok(JSONObject().put("yes", true)) }
        ActionRouter.register("can_send_record") { Ob.ok(JSONObject().put("yes", true)) }

        // 自定义:一次看全所有内部状态(控制台/自检用)
        ActionRouter.register("_pico_diagnostics") { Ob.ok(diagnostics()) }

        ActionRouter.register("_pico_config") {
            Ob.ok(Config.redactedRaw())
        }

        ActionRouter.register("_pico_logs") { params ->
            val limit = params.optInt("limit", 200).coerceIn(1, 4096)
            val arr = JSONArray()
            for (line in PicoLog.snapshot(limit)) arr.put(line)
            Ob.ok(JSONObject().put("lines", arr))
        }

        // 无头运维:主动退出账号并返回登录扫码界面
        ActionRouter.register("_pico_logout") { logout() }
        ActionRouter.register("logout") { logout() }
        ActionRouter.register("set_restart") { params -> setRestart(params) }

        // 二维码与登录运维: 获取当前登录二维码或刷新
        // 引导面板(设计 §3.2):在最近的 MainActivity 上立刻弹出 / 收起;登录后默认只自动弹一次,这里可随时再叫出来
        ActionRouter.register("_pico_show_panel") { params ->
            if (!params.optBoolean("show", true)) {
                pico.onebot.ui.PicoPanel.hideNow()
                Ob.ok(JSONObject().put("shown", false))
            } else if (pico.onebot.ui.PicoPanel.showNow()) {
                Ob.ok(JSONObject().put("shown", true))
            } else {
                Ob.failed(1404, "no MainActivity alive (QQ 首页还没起来?)")
            }
        }
        ActionRouter.register("_pico_get_qr") { params -> getQr(params) }
        ActionRouter.register("get_login_qr") { params -> getQr(params) }
        ActionRouter.register("_pico_refresh_qr") {
            val refreshed = PicoLoginManager.refreshQr()
            Ob.ok(JSONObject().put("refreshed", refreshed))
        }
        ActionRouter.register("_pico_sync_msg") {
            if (!KernelGate.ready) return@register Ob.notReady("_pico_sync_msg")
            val (ok, detail) = MsgWatchdog.triggerSyncNow("action:_pico_sync_msg")
            if (ok) {
                Ob.ok(
                    JSONObject()
                        .put("triggered", true)
                        .put("sync_triggered", Counters.syncTriggered())
                        .put("last_sync_at", Counters.lastSyncAt())
                )
            } else {
                val rc = when {
                    detail.startsWith("kernel not ready") || detail.startsWith("msgService unavailable") -> Ob.RC_NOT_READY
                    detail.startsWith("sync cooldown") || detail.startsWith("timeout") -> Ob.RC_TIMEOUT
                    else -> Ob.RC_KERNEL_ERROR
                }
                Ob.failed(rc, "触发消息同步失败: $detail")
            }
        }
    }

    private const val QR_TTL_SEC = 85
    private const val QR_REFRESH_WAIT_NS = 3_000_000_000L

    private fun getQr(params: JSONObject): JSONObject {
        if (KernelGate.state == KernelGate.State.ONLINE) {
            return Ob.ok(
                JSONObject()
                    .put("status", "already_logged_in")
                    .put("online", true)
                    .put("user_id", KernelGate.selfUin())
            )
        }
        val forceRefresh = params.optBoolean("refresh", false)
        if (forceRefresh) {
            val beforeGen = PicoLoginManager.currentQrSnapshot().generation
            if (PicoLoginManager.refreshQr()) {
                val deadlineNs = System.nanoTime() + QR_REFRESH_WAIT_NS
                while ((deadlineNs - System.nanoTime()) > 0L) {
                    val cur = PicoLoginManager.currentQrSnapshot()
                    if (cur.generation > beforeGen && !cur.expired && !cur.url.isNullOrEmpty()) {
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
        } else {
            PicoLoginManager.getOrRefreshQr(1000)
        }
        val snap = PicoLoginManager.currentQrSnapshot()
        val ageSec = if (snap.receivedAtWallMs <= 0L) {
            -1
        } else {
            ((System.nanoTime() - snap.receivedAtNs).coerceAtLeast(0L) / 1_000_000_000L).toInt()
        }
        val expired = snap.expired || (ageSec > QR_TTL_SEC)
        val hasQr = !snap.url.isNullOrEmpty()
        val status = when {
            !hasQr -> "waiting_qr"
            expired -> "expired"
            else -> "ok"
        }
        val info = JSONObject()
            .put("url", snap.url ?: "")
            .put("expired", expired)
            .put("age_seconds", ageSec)
            .put("has_qr", hasQr)
            .put("scanned", snap.scanned)
            .put("has_fragment", PicoLoginManager.qrFragmentRef?.get() != null)
            .put("has_state_fragment", PicoLoginManager.hasLoginEntry)
            .put("status", status)
        return Ob.ok(info)
    }

    private fun setRestart(params: JSONObject): JSONObject {
        if (!ContainerRestart.available()) {
            return Ob.failed(
                Ob.RC_UNSUPPORTED,
                "set_restart 仅支持已启用重启守护服务的 PicoOnebot 容器环境(不会执行破坏性登出;如需退出账号请显式调用 logout / _pico_logout)"
            )
        }
        val delayMs = if (params.isNull("delay")) 0L else params.optLong("delay", 0L).coerceIn(0L, 60_000L)
        if (delayMs > 0L) {
            if (!delayedRestartPending.compareAndSet(false, true)) {
                return Ob.failed(Ob.RC_TIMEOUT, "QQ 延迟重启请求已在队列中,请勿重复提交")
            }
            PicoLog.i("set_restart requested by action (delay=${delayMs}ms)")
            try {
                Thread({
                    try {
                        Thread.sleep(delayMs)
                        if (!ContainerRestart.request()) {
                            PicoLog.w("delayed set_restart skipped: restart request already pending or cooling down")
                        }
                    } catch (t: Throwable) {
                        PicoLog.w("delayed set_restart failed: " + t.message)
                    } finally {
                        delayedRestartPending.set(false)
                    }
                }, "pico-set-restart").apply { isDaemon = true }.start()
            } catch (t: Throwable) {
                delayedRestartPending.set(false)
                PicoLog.e("delayed set_restart thread start failed", t)
                return Ob.failed(Ob.RC_KERNEL_ERROR, "无法调度延迟重启任务: " + (t.message ?: t.javaClass.simpleName))
            }
            return Ob.ok(
                JSONObject()
                    .put("message", "QQ restart scheduled")
                    .put("delay", delayMs)
            )
        }
        if (delayedRestartPending.get()) {
            return Ob.failed(Ob.RC_TIMEOUT, "QQ 延迟重启请求已在队列中,请稍后再试")
        }
        PicoLog.i("set_restart requested by action")
        return try {
            if (!ContainerRestart.request()) {
                Ob.failed(Ob.RC_TIMEOUT, "QQ 重启请求正在处理或处于冷却期,请稍后再试")
            } else {
                Ob.ok(JSONObject().put("message", "QQ restart requested"))
            }
        } catch (t: Throwable) {
            PicoLog.e("set_restart failed", t)
            Ob.failed(Ob.RC_KERNEL_ERROR, "无法提交 QQ 重启请求: " + (t.message ?: t.javaClass.simpleName))
        }
    }

    private fun logout(): JSONObject {
        PicoLog.i("logout requested by action")
        try {
            KernelGate.onSessionLost("api_logout")
            PicoLoginManager.autoSwitchAccount = true
            PicoLoginManager.clearQr()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val app = mqq.app.MobileQQ.sMobileQQ?.peekAppRuntime()
                    app?.logout(true)
                    val ctx = mqq.app.MobileQQ.sMobileQQ
                    if (ctx != null) {
                        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        if (intent != null) ctx.startActivity(intent)
                    }
                } catch (t: Throwable) {
                    PicoLog.w("logout on main thread failed: " + t.message)
                }
            }
            return Ob.ok(JSONObject().put("message", "logout initiated"))
        } catch (t: Throwable) {
            return Ob.failed(Ob.RC_KERNEL_ERROR, "logout error: " + t.message)
        }
    }

    /**
     * 自己的账号信息。昵称走 profile 的**同步**缓存接口 ——
     * 之前这里硬编码空字符串,上游框架把它当"机器人没有名字"用了。
     */
    private fun loginInfo(): JSONObject {
        val uin = KernelGate.selfUin()
        if (uin <= 0) return Ob.notReady("get_login_info")
        return Ob.ok(JSONObject().put("user_id", uin).put("nickname", selfNick()))
    }

    private fun selfNick(): String {
        val uid = KernelGate.selfUid()
        if (uid.isEmpty()) return ""
        return try {
            KernelGate.profileService()?.getCoreAndBaseInfo("pico", arrayListOf(uid))
                ?.get(uid)?.coreInfo?.nick ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /** OneBot `get_status` 的 data,也用于 heartbeat 的 status 字段。 */
    fun statusObject(): JSONObject {
        val online = KernelGate.state == KernelGate.State.ONLINE
        return JSONObject()
            .put("online", online)
            .put("good", online)
            .put("self", JSONObject().put("platform", "watch").put("user_id", KernelGate.selfUin()))
            .put("pico", JSONObject()
                .put("state", KernelGate.state.name)
                .put("uptime_ms", System.currentTimeMillis() - PicoBoot.startedAt)
                .put("connections", Transport.count())
                .put("last_recv_at", Counters.lastRecvAt())
                .put("silence_ms", MsgWatchdog.silenceMs())
                .put("read_reports_sent", Counters.readReportsSent())
                .put("read_reports_failed", Counters.readReportsFailed())
                .put("sync_triggered", Counters.syncTriggered())
                .put("last_sync_at", Counters.lastSyncAt())
                .put("event_queue", EventBus.stats())
                .put("msg_pipeline", MsgPipeline.stats())
            )
    }

    fun diagnostics(): JSONObject {
        val kernel = JSONObject()
        for ((k, v) in KernelGate.diagnostics()) kernel.put(k, v)

        val listener = JSONObject()
        for ((k, v) in MsgListenerProxy.callStats()) listener.put(k, v)

        return JSONObject()
            .put("version", PicoBoot.VERSION)
            .put("state", KernelGate.state.name)
            .put("uptime_ms", System.currentTimeMillis() - PicoBoot.startedAt)
            .put("kernel", kernel)
            .put("listener_calls", listener)
            .put("event_queue", EventBus.stats())
            .put("msg_pipeline", MsgPipeline.stats())
            .put("read_reporter", ReadReporter.stats())
            .put("msg_watchdog", MsgWatchdog.stats())
            .put("recent_contacts", MsgWatchdog.recentContactSummary())
            .put("uid_cache", UinUidStore.stats())
            .put("msgid_store", JSONObject().put("size", MsgIdStore.size()))
            .put("send_pending", SendTracker.pending())
            .put("media_files", pico.onebot.media.MediaFiles.stats())
            // 媒体外链是否真的可用(配了 media_base_url,或有在听的 OneBot 端口)。
            // 纯反向 WS 部署下这曾经是"取图 URL 静默不可用"的最可能来源,放到诊断里可见。
            .put("media_url_available", PicoBoot.mediaUrlAvailable())
            .put("media_download", JSONObject(pico.onebot.media.MediaDownloader.stats() as Map<*, *>))
            .put("media_refs", pico.onebot.media.MediaRefStore.size())
            .put("nick_cache", pico.onebot.kernel.NickStore.stats())
            .put("contacts", pico.onebot.kernel.ContactStore.stats())
            .put("reporter", pico.onebot.net.HttpReporter.stats())
            .put("forward_store", pico.onebot.msg.ForwardStore.size())
            .put("connections", JSONArray(Transport.describe()))
            .put("capabilities", JSONArray(ActionRouter.names()))
    }
}
