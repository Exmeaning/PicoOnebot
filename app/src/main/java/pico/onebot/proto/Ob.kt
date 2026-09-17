package pico.onebot.proto

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.kernel.KernelGate

/**
 * OneBot 11 的响应信封与错误码。**任何失败都要带错误码返回,不静默失败。**
 */
object Ob {

    /** 内核未就绪 / 未登录 */
    const val RC_NOT_READY = 1400
    /** 本端不支持该 action */
    const val RC_UNSUPPORTED = 1404
    /** 鉴权失败 */
    const val RC_UNAUTHORIZED = 1403
    /** 内核调用超时 */
    const val RC_TIMEOUT = 1200
    /** 内核返回非 0 */
    const val RC_KERNEL_ERROR = 1500
    /** 参数非法 */
    const val RC_BAD_PARAM = 10001

    fun ok(data: Any? = null): JSONObject = JSONObject()
        .put("status", "ok")
        .put("retcode", 0)
        .put("data", data ?: JSONObject.NULL)

    fun failed(retcode: Int, message: String): JSONObject = JSONObject()
        .put("status", "failed")
        .put("retcode", retcode)
        .put("data", JSONObject.NULL)
        .put("message", message)
        .put("wording", message)

    fun notReady(what: String): JSONObject =
        failed(RC_NOT_READY, "$what:内核未就绪(当前状态 " + KernelGate.state + ")")

    /** 事件公共字段:time / self_id / post_type。 */
    fun event(postType: String): JSONObject = JSONObject()
        .put("time", System.currentTimeMillis() / 1000)
        .put("self_id", KernelGate.selfUin())
        .put("post_type", postType)

    fun arrayOf(vararg items: Any): JSONArray {
        val a = JSONArray()
        for (i in items) a.put(i)
        return a
    }
}
