package pico.onebot.kernel

import com.tencent.qqnt.kernel.nativeinterface.IKernelBuddyListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener
import pico.onebot.core.PicoLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 群 / 好友监听器。和 [MsgListenerProxy] 同一套路:接口方法多且随版本增删,
 * 用 [Proxy] 动态实现 + 按方法名分发,内核加方法既编译得过也不会 AbstractMethodError。
 *
 * 这里只做回填缓存,**不产生 OneBot 事件**(群管理/请求事件不在当前范围内)。
 * 同样地,任何异常都不许抛回内核。
 */
object ContactListeners {

    private val callCounts = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    private var groupListenerId: Long = 0

    @Volatile
    private var buddyListenerId: Long = 0

    val registered: Boolean get() = groupListenerId != 0L || buddyListenerId != 0L

    /** 幂等:已注册过就直接返回。调用时机是 KernelGate 探测到 ONLINE 之后。 */
    fun register() {
        if (groupListenerId == 0L) {
            KernelGate.groupService()?.let { svc ->
                try {
                    val iface = IKernelGroupListener::class.java
                    val p = Proxy.newProxyInstance(
                        iface.classLoader, arrayOf(iface), Handler(::dispatchGroup)
                    ) as IKernelGroupListener
                    groupListenerId = svc.addKernelGroupListener(p)
                    PicoLog.i("kernel group listener registered, id=" + groupListenerId)
                } catch (t: Throwable) {
                    PicoLog.w("addKernelGroupListener failed", t)
                }
            }
        }
        if (buddyListenerId == 0L) {
            KernelGate.buddyService()?.let { svc ->
                try {
                    val iface = IKernelBuddyListener::class.java
                    val p = Proxy.newProxyInstance(
                        iface.classLoader, arrayOf(iface), Handler(::dispatchBuddy)
                    ) as IKernelBuddyListener
                    buddyListenerId = svc.addKernelBuddyListener(p)
                    PicoLog.i("kernel buddy listener registered, id=" + buddyListenerId)
                } catch (t: Throwable) {
                    PicoLog.w("addKernelBuddyListener failed", t)
                }
            }
        }
    }

    /** 会话丢失后要重新注册,否则重新登录上来收不到名册回填。 */
    fun reset() {
        groupListenerId = 0
        buddyListenerId = 0
    }

    private fun dispatchGroup(name: String, args: Array<out Any?>?) {
        when (name) {
            "onGroupListUpdate" ->
                ContactStore.onGroupListUpdate(args?.getOrNull(0), args?.getOrNull(1) as? ArrayList<*>)

            "onGroupDetailInfoChange" ->
                ContactStore.onGroupDetailInfo(args?.getOrNull(0))

            // onMemberInfoChange(groupCode, DataSource, HashMap<uid, MemberInfo>)
            "onMemberInfoChange" ->
                ContactStore.onMemberInfoChange(args?.getOrNull(2))
        }
    }

    private fun dispatchBuddy(name: String, args: Array<out Any?>?) {
        when (name) {
            "onBuddyListChange" ->
                ContactStore.onBuddyListChange(args?.getOrNull(0) as? ArrayList<*>)

            // onNickUpdated(uid, nick) / onBuddyRemarkUpdated(uid, remark)
            "onNickUpdated", "onBuddyRemarkUpdated" ->
                NickStore.feed(args?.getOrNull(0) as? String, args?.getOrNull(1) as? String)
        }
    }

    fun callStats(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for ((k, v) in callCounts) out[k] = v.get()
        return out
    }

    private class Handler(private val sink: (String, Array<out Any?>?) -> Unit) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val name = method.name
            when (name) {
                "toString" -> return "PicoContactListener"
                "hashCode" -> return System.identityHashCode(proxy)
                "equals" -> return proxy === args?.getOrNull(0)
            }
            callCounts.getOrPut(name) { AtomicLong() }.incrementAndGet()
            try {
                sink(name, args)
            } catch (t: Throwable) {
                PicoLog.w("contact listener $name failed: " + t.javaClass.simpleName + " " + t.message)
            }
            return defaultValue(method.returnType)
        }

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
