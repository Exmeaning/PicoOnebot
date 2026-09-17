package pico.onebot.net

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.action.SystemActions
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.kernel.KernelGate
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
