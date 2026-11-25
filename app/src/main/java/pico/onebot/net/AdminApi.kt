package pico.onebot.net

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.action.ActionRouter
import pico.onebot.action.SystemActions
import pico.onebot.core.Config
import pico.onebot.core.Counters
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
import pico.onebot.kernel.MsgWatchdog
import pico.onebot.proto.Ob

/**
 * 控制台后端(`/api/pico/…`)。控制台本体是静态文件,挂在 `filesDir/pico-onebot/webui/`。
 */
object AdminApi {

    fun handle(subPath: String, query: Map<String, String>, body: ByteArray): JSONObject {
        val path = subPath.trimEnd('/').ifEmpty { "/" }
        return when (path) {
            "/", "/status" -> Ob.ok(SystemActions.statusObject())

            "/diagnostics" -> Ob.ok(SystemActions.diagnostics())

            "/logs" -> {
                val limit = (query["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 4096)
                val arr = JSONArray()
                for (line in PicoLog.snapshot(limit)) arr.put(line)
                Ob.ok(JSONObject().put("lines", arr))
            }

            "/config" -> {
                if (body.isEmpty()) {
                    Ob.ok(Config.redactedRaw())
                } else {
                    try {
                        val patch = JSONObject(String(body, Charsets.UTF_8))
                        patch.optJSONObject("webui")?.apply {
                            remove("token")
                            remove("passwordSalt")
                            remove("passwordHash")
                            remove("passwordInitialized")
                        }
                        val network = patch.optJSONObject("network")
                        val errors = if (network == null) emptyList() else Config.validateNetwork(network)
                        if (errors.isNotEmpty()) {
                            return Ob.failed(Ob.RC_BAD_PARAM, errors.joinToString("；"))
                        }
                        Config.applyAppConfig(patch)
                        PicoLog.i("config patched via admin api: " + patch.keys().asSequence().joinToString(","))
                        Ob.ok(JSONObject().put("restart_required", true))
                    } catch (t: Throwable) {
                        Ob.failed(Ob.RC_BAD_PARAM, "config 解析失败: " + t.message)
                    }
                }
            }

            "/selftest" -> Ob.ok(selftest())

            "/sync" -> {
                val (ok, detail) = MsgWatchdog.triggerSyncNow("admin_api")
                if (ok) {
                    Ob.ok(
                        JSONObject()
                            .put("triggered", true)
                            .put("sync_triggered", Counters.syncTriggered())
                            .put("last_sync_at", Counters.lastSyncAt())
                    )
                } else {
                    Ob.failed(Ob.RC_KERNEL_ERROR, "触发消息同步失败: $detail")
                }
            }

            "/mark_read" -> {
                val params = try {
                    if (body.isNotEmpty()) JSONObject(String(body, Charsets.UTF_8))
                    else JSONObject().apply {
                        for ((k, v) in query) if (k != "access_token") put(k, v)
                    }
                } catch (t: Throwable) {
                    return Ob.failed(Ob.RC_BAD_PARAM, "参数解析失败: " + t.message)
                }
                ActionRouter.call("mark_msg_as_read", params)
            }

            else -> Ob.failed(Ob.RC_UNSUPPORTED, "unknown admin path: $subPath")
        }
    }

    /** 自检:会话可达 / 监听器在册 / 队列水位 / 映射命中率,一次看完。 */
    private fun selftest(): JSONObject {
        val checks = JSONArray()
        fun check(name: String, ok: Boolean, detail: String) {
            checks.put(JSONObject().put("name", name).put("ok", ok).put("detail", detail))
        }

        val d = SystemActions.diagnostics()
        val kernel = d.optJSONObject("kernel") ?: JSONObject()

        check("transport", Transport.count() >= 0, "connections=" + Transport.count())
        check("session", kernel.optBoolean("session", false), "KernelServiceUtil 返回的会话对象")
        check("logged_in", kernel.optBoolean("logged_in", false), "AppRuntime.isLogin && uin>0")
        check("msg_listener", kernel.optLong("listener_id", 0) != 0L, "listenerId=" + kernel.optLong("listener_id", 0))

        val q = d.optJSONObject("event_queue") ?: JSONObject()
        check("event_queue", q.optLong("dropped", 0) == 0L, "dropped=" + q.optLong("dropped", 0) + " pending=" + q.optLong("pending", 0))

        val uid = d.optJSONObject("uid_cache") ?: JSONObject()
        check("uid_cache", true, "size=" + uid.optInt("size") + " hits=" + uid.optLong("hits") + " misses=" + uid.optLong("misses"))

        val wd = d.optJSONObject("msg_watchdog") ?: JSONObject()
        val silenceMs = wd.optLong("silence_ms", 0L)
        val thresholdMs = Config.silenceSyncThresholdMs
        val silenceOk = thresholdMs <= 0L || silenceMs < thresholdMs
        check(
            "msg_silence",
            silenceOk,
            "last_recv_at=" + wd.optLong("last_recv_at", 0L) +
                " silence_ms=" + silenceMs +
                " sync_triggered=" + wd.optLong("sync_triggered", 0L)
        )

        val rr = d.optJSONObject("read_reporter") ?: JSONObject()
        check(
            "read_reporter",
            true,
            "enabled=" + rr.optBoolean("enabled", true) +
                " sent=" + rr.optLong("sent", 0L) +
                " failed=" + rr.optLong("failed", 0L) +
                " pending=" + rr.optInt("pending_messages", 0)
        )

        var allOk = true
        for (i in 0 until checks.length()) {
            if (!checks.optJSONObject(i).optBoolean("ok")) allOk = false
        }
        return JSONObject()
            .put("state", KernelGate.state.name)
            .put("all_ok", allOk)
            .put("checks", checks)
    }
}
