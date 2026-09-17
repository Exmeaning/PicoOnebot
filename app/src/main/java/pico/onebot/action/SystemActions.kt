package pico.onebot.action

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.PicoBoot
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.event.EventBus
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.MsgListenerProxy
import pico.onebot.kernel.MsgPipeline
import pico.onebot.kernel.PicoLoginManager
import pico.onebot.kernel.SendTracker
import pico.onebot.kernel.UinUidStore
import pico.onebot.msg.MsgIdStore
import pico.onebot.net.Transport
import pico.onebot.proto.Ob

/** 不依赖登录就能回答的动作 —— M1 的验收面。 */
object SystemActions {

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
        ActionRouter.register("set_restart") { logout() }

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
    }

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
            PicoLoginManager.refreshQr()
        }
        val url = PicoLoginManager.getOrRefreshQr(if (forceRefresh) 3000 else 1000)
        val info = PicoLoginManager.info()
        if (url != null) {
            info.put("status", "ok")
        } else {
            info.put("status", "waiting_qr")
        }
        return Ob.ok(info)
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
            .put("uid_cache", UinUidStore.stats())
            .put("msgid_store", JSONObject().put("size", MsgIdStore.size()))
            .put("send_pending", SendTracker.pending())
            .put("media_files", pico.onebot.media.MediaFiles.stats())
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
