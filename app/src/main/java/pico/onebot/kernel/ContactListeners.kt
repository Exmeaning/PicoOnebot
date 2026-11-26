package pico.onebot.kernel

import com.tencent.qqnt.kernel.nativeinterface.IKernelBuddyListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession
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
    private val lock = Any()

    @Volatile
    private var groupListenerId: Long = 0

    @Volatile
    private var buddyListenerId: Long = 0

    @Volatile
    private var attachedSession: IQQNTWrapperSession? = null

    @Volatile
    private var attachedEpoch: Long = -1L

    /**
     * **两个都注册成功**才算就绪。
     */
    val registered: Boolean get() = groupListenerId != 0L && buddyListenerId != 0L

    /** 幂等:已注册过就直接返回。调用时机是 KernelGate 探测到 ONLINE 之后。 */
    fun register() {
        val session = KernelGate.session() ?: return
        register(session, KernelGate.currentEpoch())
    }

    /** 代际守护版注册:若在注册期间会话失效/切号,立即摘除新注册的原生监听器。 */
    fun register(session: IQQNTWrapperSession, expectedEpoch: Long) {
        val needGroup = synchronized(lock) {
            if (!KernelGate.isEpochCurrent(expectedEpoch)) return
            groupListenerId == 0L || attachedEpoch != expectedEpoch
        }
        if (needGroup) {
            val svc = try { session.groupService } catch (_: Throwable) { null }
            if (svc != null) {
                try {
                    val iface = IKernelGroupListener::class.java
                    val p = Proxy.newProxyInstance(
                        iface.classLoader, arrayOf(iface), Handler(::dispatchGroup)
                    ) as IKernelGroupListener
                    val id = svc.addKernelGroupListener(p)
                    if (id != 0L) {
                        val committed = synchronized(lock) {
                            if (!KernelGate.isEpochCurrent(expectedEpoch) ||
                                (attachedEpoch != -1L && attachedEpoch != expectedEpoch) ||
                                groupListenerId != 0L
                            ) {
                                false
                            } else {
                                groupListenerId = id
                                attachedSession = session
                                attachedEpoch = expectedEpoch
                                true
                            }
                        }
                        if (committed) {
                            PicoLog.i("kernel group listener registered, id=$id")
                        } else {
                            PicoLog.w("discarding stale/duplicate group listener id=$id")
                            runCatching { svc.removeKernelGroupListener(id) }
                                .onFailure { PicoLog.d("discard group listener($id) ignored: ${it.message}") }
                        }
                    }
                } catch (t: Throwable) {
                    PicoLog.w("addKernelGroupListener failed", t)
                }
            }
        }

        val needBuddy = synchronized(lock) {
            if (!KernelGate.isEpochCurrent(expectedEpoch)) return
            buddyListenerId == 0L || attachedEpoch != expectedEpoch
        }
        if (needBuddy) {
            val svc = try { session.buddyService } catch (_: Throwable) { null }
            if (svc != null) {
                try {
                    val iface = IKernelBuddyListener::class.java
                    val p = Proxy.newProxyInstance(
                        iface.classLoader, arrayOf(iface), Handler(::dispatchBuddy)
                    ) as IKernelBuddyListener
                    val id = svc.addKernelBuddyListener(p)
                    if (id != 0L) {
                        val committed = synchronized(lock) {
                            if (!KernelGate.isEpochCurrent(expectedEpoch) ||
                                (attachedEpoch != -1L && attachedEpoch != expectedEpoch) ||
                                buddyListenerId != 0L
                            ) {
                                false
                            } else {
                                buddyListenerId = id
                                attachedSession = session
                                attachedEpoch = expectedEpoch
                                true
                            }
                        }
                        if (committed) {
                            PicoLog.i("kernel buddy listener registered, id=$id")
                        } else {
                            PicoLog.w("discarding stale/duplicate buddy listener id=$id")
                            runCatching { svc.removeKernelBuddyListener(id) }
                                .onFailure { PicoLog.d("discard buddy listener($id) ignored: ${it.message}") }
                        }
                    }
                } catch (t: Throwable) {
                    PicoLog.w("addKernelBuddyListener failed", t)
                }
            }
        }
    }

    /** 会话丢失后反注册旧监听器并复位状态,防止原生监听器泄漏与重复回调。 */
    fun reset() = reset(null)

    fun reset(oldSession: IQQNTWrapperSession?) {
        val gId: Long
        val bId: Long
        val targetSession: IQQNTWrapperSession?
        synchronized(lock) {
            gId = groupListenerId
            bId = buddyListenerId
            targetSession = oldSession ?: attachedSession
            groupListenerId = 0L
            buddyListenerId = 0L
            attachedSession = null
            attachedEpoch = -1L
        }
        if (targetSession != null) {
            if (gId != 0L) {
                runCatching { targetSession.groupService?.removeKernelGroupListener(gId) }
                    .onFailure { PicoLog.d("removeKernelGroupListener($gId) ignored: ${it.message}") }
            }
            if (bId != 0L) {
                runCatching { targetSession.buddyService?.removeKernelBuddyListener(bId) }
                    .onFailure { PicoLog.d("removeKernelBuddyListener($bId) ignored: ${it.message}") }
            }
        }
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
