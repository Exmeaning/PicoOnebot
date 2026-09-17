package pico.onebot.action

import org.json.JSONObject
import pico.onebot.core.PicoLog
import pico.onebot.proto.Ob

/**
 * OneBot action 路由表。**未注册的 action 明确回 1404,不静默失败。**
 * `get_status.capabilities` 直接读这张表,所以文档与实现不会对不上。
 */
object ActionRouter {

    fun interface Handler {
        /** @return 完整的 OneBot 响应体(用 [Ob.ok] / [Ob.failed] 构造)。 */
        fun handle(params: JSONObject): JSONObject
    }

    private val handlers = LinkedHashMap<String, Handler>()

    fun register(name: String, handler: Handler) {
        handlers[name] = handler
    }

    fun has(action: String): Boolean = handlers.containsKey(canonical(action))

    fun names(): List<String> = ArrayList(handlers.keys)

    fun call(action: String, params: JSONObject): JSONObject {
        val name = canonical(action)
        val h = handlers[name]
            ?: return Ob.failed(Ob.RC_UNSUPPORTED, "action 未实现: $action")
        return try {
            h.handle(params)
        } catch (t: Throwable) {
            PicoLog.e("action $name failed", t)
            Ob.failed(Ob.RC_KERNEL_ERROR, t.javaClass.simpleName + ": " + t.message)
        }
    }

    /** 兼容 `_async` 后缀与 `.` 分隔的变体写法。 */
    private fun canonical(action: String): String {
        var a = action.trim().trimStart('/')
        if (a.endsWith("_async")) a = a.substring(0, a.length - 6)
        if (a.endsWith("_rate_limited")) a = a.substring(0, a.length - 13)
        return a
    }
}
