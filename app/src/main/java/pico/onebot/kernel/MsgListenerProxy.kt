package pico.onebot.kernel

import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import pico.onebot.core.PicoLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * `IKernelMsgListener` 有 68 个抽象方法,且**会随 QQ 版本增删**。
 * 用 [Proxy] 动态实现 + 按方法名分发:内核加方法既不会编译不过,也不会 AbstractMethodError。
 *
 * 这里只做"拷贝 + 入队",重活交给 [MsgPipeline] 的单线程 —— 内核回调线程不能被拖住。
 */
object MsgListenerProxy {

    private val callCounts = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    private var proxy: IKernelMsgListener? = null

    /** @return listenerId,0 表示失败。 */
    fun register(msgService: IKernelMsgService): Long {
        return try {
            val p = proxy ?: create().also { proxy = it }
            val id = msgService.addKernelMsgListener(p)
            id
        } catch (t: Throwable) {
            PicoLog.e("addKernelMsgListener failed", t)
            0L
        }
    }

    private fun create(): IKernelMsgListener {
        val iface = IKernelMsgListener::class.java
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), Handler()) as IKernelMsgListener
    }

    fun callStats(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((k, v) in callCounts) out[k] = v.get()
        return out
    }

    private class Handler : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val name = method.name
            when (name) {
                "toString" -> return "PicoKernelMsgListener"
                "hashCode" -> return System.identityHashCode(proxy)
                "equals" -> return proxy === args?.getOrNull(0)
            }
            callCounts.getOrPut(name) { AtomicLong() }.incrementAndGet()
            try {
                dispatch(name, args)
            } catch (t: Throwable) {
                // 绝不把异常抛回内核 —— JNI 侧对 Java 异常的容忍度未知
                PicoLog.w("listener $name failed: " + t.javaClass.simpleName + " " + t.message)
            }
            return defaultValue(method.returnType)
        }

        private fun dispatch(name: String, args: Array<out Any?>?) {
            when (name) {
                "onRecvMsg", "onMsgInfoListAdd", "onRecvOnlineFileMsg" ->
                    records(args)?.let { MsgPipeline.onIncoming(name, it) }

                "onMsgInfoListUpdate" ->
                    records(args)?.let { MsgPipeline.onUpdate(it) }

                "onAddSendMsg" ->
                    (args?.getOrNull(0) as? MsgRecord)?.let { MsgPipeline.onSelfSent(it) }

                "onKickedOffLine" ->
                    KernelGate.onSessionLost("onKickedOffLine")

                "onRecvSysMsg" ->
                    MsgPipeline.onSysMsg(args?.getOrNull(0))

                "onRichMediaDownloadComplete" ->
                    pico.onebot.media.MediaDownloader.onComplete(args?.getOrNull(0))

                // onSendMsgError(msgId, Contact, errType, errMsg):唯一能识破
                // "回调回 0 但其实没发出去"的通道
                "onSendMsgError" -> MsgPipeline.onSendError(
                    args?.getOrNull(0) as? Long ?: 0L,
                    args?.getOrNull(1),
                    args?.getOrNull(2) as? Int ?: 0,
                    args?.getOrNull(3) as? String ?: ""
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun records(args: Array<out Any?>?): ArrayList<MsgRecord>? =
            args?.getOrNull(0) as? ArrayList<MsgRecord>

        private fun defaultValue(type: Class<*>): Any? = when {
            !type.isPrimitive -> null
            type == java.lang.Boolean.TYPE -> false
            type == java.lang.Byte.TYPE -> 0.toByte()
            type == java.lang.Character.TYPE -> 0.toChar()
            type == java.lang.Short.TYPE -> 0.toShort()
            type == java.lang.Integer.TYPE -> 0
            type == java.lang.Long.TYPE -> 0L
            type == java.lang.Float.TYPE -> 0f
            type == java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }
}
