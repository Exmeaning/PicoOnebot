package pico.onebot.kernel

import com.tencent.qqnt.kernel.nativeinterface.IKernelBuddyService
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupService
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService
import com.tencent.qqnt.kernel.nativeinterface.IKernelProfileService
import com.tencent.qqnt.kernel.nativeinterface.IKernelRecentContactService
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession
import pico.onebot.core.PicoLog
import pico.onebot.event.MetaEvents
import java.lang.reflect.Modifier

/**
 * 与宿主耦合的唯一入口之一(另一个是 hook 包)。
 *
 * 会话解析刻意**不写死混淆过的方法名**:`KernelServiceUtil.g()` 这种单字母名会随
 * base APK 版本漂移,但它的**返回类型** `IQQNTWrapperSession` 来自不混淆的
 * `nativeinterface` 包 —— 所以按返回类型反射查找,换版本不必改代码。
 */
object KernelGate {

    enum class State { INIT, TRANSPORT_UP, SESSION_READY, LOGGED_IN, ONLINE, LOGGED_OUT }

    @Volatile
    var state: State = State.INIT
        private set

    @Volatile
    private var sessionRef: IQQNTWrapperSession? = null

    @Volatile
    private var selfUinCache: Long = 0

    @Volatile
    private var selfUidCache: String = ""

    @Volatile
    private var listenerId: Long = 0

    @Volatile
    var lastError: String = ""
        private set

    private val watchWakeup = java.util.concurrent.Semaphore(0)

    @Volatile
    private var watchThread: Thread? = null

    @Volatile
    private var cachedSessionMethod: java.lang.reflect.Method? = null

    @Volatile
    private var cachedSessionField: java.lang.reflect.Field? = null

    /**
     * 会话代数。每次 [onSessionLost] 自增,用于让正在执行的 [probe] 立刻作废。
     *
     * 没有它时的竞态:probe 在第 123 行通过检查后,内核线程的 onKickedOffLine
     * 调 onSessionLost 清掉 listenerId 并置 LOGGED_OUT;probe 继续往下跑,
     * 对已失效的 session 重新注册监听器并置 ONLINE。之后要等一个 5s 的
     * ONLINE 轮询间隔才会发现,期间动作会打到已登出的会话上。
     */
    @Volatile
    private var sessionEpoch: Long = 0

    private const val KERNEL_SERVICE_UTIL = "com.tencent.qqnt.msg.KernelServiceUtil"

    // ---------------- 状态机 ----------------

    private val stateListeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()

    /** 订阅状态机变化(引导面板等 UI 用);返回取消函数。回调线程不定,UI 自己 post。 */
    fun addStateListener(l: (State) -> Unit): () -> Unit {
        stateListeners.add(l)
        return { stateListeners.remove(l) }
    }

    fun setState(next: State) {
        if (state == next) return
        val from = state
        state = next
        MetaEvents.stateChanged(from.name, next.name)
        for (l in stateListeners) {
            try {
                l(next)
            } catch (t: Throwable) {
                PicoLog.w("state listener failed: " + t.message)
            }
        }
    }

    val ready: Boolean get() = state == State.ONLINE || state == State.LOGGED_IN

    fun startWatch() {
        Thread({ watchLoop() }, "pico-kernel-watch").apply { isDaemon = true }.start()
    }

    private fun wakeWatch() {
        if (Thread.currentThread() !== watchThread && watchWakeup.availablePermits() == 0) {
            watchWakeup.release()
        }
    }

    private fun watchLoop() {
        watchThread = Thread.currentThread()
        var delay = 200L
        var prevState = state
        while (true) {
            watchWakeup.drainPermits()
            try {
                probe()
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName + ": " + t.message
                PicoLog.d("probe failed: " + lastError)
            }
            val cur = state
            delay = when (cur) {
                State.ONLINE -> 5000L
                State.SESSION_READY, State.LOGGED_IN -> 200L
                else -> if (prevState == State.ONLINE) 200L else minOf(delay * 2, 1000L)
            }
            prevState = cur
            try {
                watchWakeup.tryAcquire(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (t: InterruptedException) {
                return
            }
        }
    }

    /** 一次探测:会话 → 登录态 → 监听器注册。每步都可独立失败并在下一轮重试。 */
    private fun probe() {
        val epochAtStart = sessionEpoch
        val cached = sessionRef
        val live = findSessionByReturnType()
        if (live == null || !loggedIn()) {
            if (state == State.ONLINE || state == State.LOGGED_IN || state == State.SESSION_READY ||
                cached != null || listenerId != 0L
            ) {
                onSessionLost(if (live == null) "no_session" else "not_logged_in")
            } else if (state == State.INIT) {
                setState(State.TRANSPORT_UP)
            }
            return
        }
        if (cached != null && cached !== live) {
            onSessionLost("session_replaced")
        }
        // 会话在本轮探测期间被作废(被踢下线 / 换号替换):上面拿到的 live 快照
        // 已经过期,不能再写 sessionRef,更不能把状态从 LOGGED_OUT 推回
        // SESSION_READY / LOGGED_IN —— 那会出现一段假 ONLINE 窗口,动作打到
        // 已登出的会话上,直到下一轮 200ms 轮询才纠正回来。
        if (sessionEpoch != epochAtStart) return
        if (sessionRef !== live) sessionRef = live

        if (state == State.INIT || state == State.TRANSPORT_UP || state == State.LOGGED_OUT) {
            setState(State.SESSION_READY)
        }

        val uin = readSelfUin()
        // 读 uin 期间同样可能被踢:先校验再写缓存,避免把失效会话的 uin 留在缓存里
        if (sessionEpoch != epochAtStart) return
        if (uin <= 0) return
        selfUinCache = uin
        if (state == State.SESSION_READY) setState(State.LOGGED_IN)

        if (listenerId == 0L) {
            val msg = msgService() ?: return
            val id = MsgListenerProxy.register(msg)
            if (id != 0L) {
                // 注册期间可能已经被踢下线:此时 sessionRef 已被 onSessionLost 清空,
                // 这颗监听器属于失效会话,必须立刻摘掉,也不能置 ONLINE。
                if (sessionEpoch != epochAtStart || sessionRef == null) {
                    PicoLog.w("probe: session lost during listener registration (id=$id), discarding")
                    // 与 onSessionLost 里同一调用形式:摘掉这颗已属失效会话的监听器
                    runCatching { live.msgService?.removeKernelMsgListener(id) }
                        .onFailure { PicoLog.d("discard listener($id) ignored: ${it.message}") }
                    return
                }
                listenerId = id
                PicoLog.i("kernel msg listener registered, id=$id")
                setState(State.ONLINE)
            }
        } else if (state == State.LOGGED_IN && sessionEpoch == epochAtStart) {
            setState(State.ONLINE)
        }

        // 名册监听器单独注册:它失败不该拖垮消息通路,所以放在消息监听器之后、且各自幂等
        if (state == State.ONLINE && !ContactListeners.registered) ContactListeners.register()
    }

    /** 被踢/会话失效时回落,下一轮探测会重新注册监听器。 */
    fun onSessionLost(reason: String) {
        val oldId: Long
        val oldSession: IQQNTWrapperSession?
        synchronized(this) {
            if (listenerId == 0L && sessionRef == null && state == State.LOGGED_OUT) return
            oldId = listenerId
            oldSession = sessionRef
            listenerId = 0
            sessionRef = null
            selfUinCache = 0
            selfUidCache = ""
            // 让正在进行中的 probe 作废(见 sessionEpoch 的注释)
            sessionEpoch++
        }
        PicoLog.w("session lost: $reason")
        if (oldId != 0L && oldSession != null) {
            runCatching { oldSession.msgService?.removeKernelMsgListener(oldId) }
                .onFailure { PicoLog.d("removeKernelMsgListener($oldId) ignored: ${it.message}") }
        }
        ContactListeners.reset()
        // 名册是"当前账号的视角",不清就会在换号后把上一个人的群/好友返回给上游
        ContactStore.reset()
        ReadReporter.reset()
        MsgWatchdog.reset()
        setState(State.LOGGED_OUT)
        wakeWatch()
    }

    // ---------------- 会话与服务 ----------------

    fun session(): IQQNTWrapperSession? {
        sessionRef?.let { return it }
        val found = findSessionByReturnType()
        if (found != null) sessionRef = found
        return found
    }

    /**
     * 会话解析结果的可读说明。登录前拿不到会话是**正常**的,但"为什么拿不到"
     * 必须说得出来(类没了 / 没有这种 getter / getter 返回 null),否则 M2 排查就是瞎猜。
     */
    @Volatile
    var sessionProbe: String = "not-probed"
        private set

    private fun findSessionByReturnType(): IQQNTWrapperSession? {
        cachedSessionMethod?.let { m ->
            try {
                val v = m.invoke(null) as? IQQNTWrapperSession
                if (v != null) {
                    sessionProbe = "ok:method " + m.name + "()"
                    return v
                }
            } catch (t: Throwable) {
                cachedSessionMethod = null
            }
        }
        cachedSessionField?.let { f ->
            try {
                val v = f.get(null) as? IQQNTWrapperSession
                if (v != null) {
                    sessionProbe = "ok:field " + f.name
                    return v
                }
            } catch (t: Throwable) {
                cachedSessionField = null
            }
        }
        val cls = try {
            Class.forName(KERNEL_SERVICE_UTIL)
        } catch (t: Throwable) {
            sessionProbe = "class-missing:$KERNEL_SERVICE_UTIL(base APK 变了?)"
            return null
        }
        var candidates = 0
        try {
            for (m in cls.declaredMethods) {
                if (!Modifier.isStatic(m.modifiers)) continue
                if (m.parameterTypes.isNotEmpty()) continue
                if (!IQQNTWrapperSession::class.java.isAssignableFrom(m.returnType)) continue
                candidates++
                m.isAccessible = true
                val v = m.invoke(null)
                if (v != null) {
                    cachedSessionMethod = m
                    sessionProbe = "ok:method " + m.name + "()"
                    return v as IQQNTWrapperSession
                }
            }
            // 兜底:有些版本把会话放在静态字段上
            for (f in cls.declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                if (!IQQNTWrapperSession::class.java.isAssignableFrom(f.type)) continue
                candidates++
                f.isAccessible = true
                val v = f.get(null)
                if (v != null) {
                    cachedSessionField = f
                    sessionProbe = "ok:field " + f.name
                    return v as IQQNTWrapperSession
                }
            }
        } catch (t: Throwable) {
            lastError = "findSession: " + t.javaClass.simpleName + " " + t.message
            sessionProbe = "throw:" + lastError
            return null
        }
        sessionProbe = if (candidates == 0) {
            "no-static-getter-returning-IQQNTWrapperSession(共 " + cls.declaredMethods.size + " 个方法)"
        } else {
            "getter-returned-null(候选 $candidates 个,登录前属正常)"
        }
        return null
    }

    fun msgService(): IKernelMsgService? = safe { session()?.msgService }
    fun groupService(): IKernelGroupService? = safe { session()?.groupService }
    fun buddyService(): IKernelBuddyService? = safe { session()?.buddyService }
    fun profileService(): IKernelProfileService? = safe { session()?.profileService }
    fun recentContactService(): IKernelRecentContactService? = safe { session()?.recentContactService }

    private fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (t: Throwable) {
        lastError = t.javaClass.simpleName + ": " + t.message
        null
    }

    // ---------------- 自身身份 ----------------

    fun selfUin(): Long {
        if (selfUinCache > 0) return selfUinCache
        val v = readSelfUin()
        if (v > 0) selfUinCache = v
        return selfUinCache
    }

    fun selfUid(): String {
        if (selfUidCache.isNotEmpty()) return selfUidCache
        val v = readSelfUid()
        if (v.isNotEmpty()) selfUidCache = v
        return selfUidCache
    }

    /** 走 mqq 的 AppRuntime;未登录时返回 0(这是正常态,不是错误)。 */
    private fun readSelfUin(): Long = try {
        val rt = appRuntime()
        if (rt == null || !rt.isLogin) 0L else {
            val uin = rt.currentAccountUin
            if (uin.isNullOrEmpty()) 0L else uin.toLongOrNull() ?: 0L
        }
    } catch (t: Throwable) {
        0L
    }

    private fun readSelfUid(): String = try {
        val rt = appRuntime()
        if (rt == null || !rt.isLogin) "" else rt.currentUid ?: ""
    } catch (t: Throwable) {
        ""
    }

    private fun appRuntime(): mqq.app.AppRuntime? = try {
        mqq.app.MobileQQ.getMobileQQ()?.peekAppRuntime()
    } catch (t: Throwable) {
        null
    }

    fun loggedIn(): Boolean = try {
        val rt = appRuntime()
        rt != null && rt.isLogin
    } catch (t: Throwable) {
        false
    }

    fun diagnostics(): Map<String, Any> = linkedMapOf(
        "state" to state.name,
        "session" to (sessionRef != null),
        "session_probe" to sessionProbe,
        "listener_id" to listenerId,
        "self_uin" to selfUin(),
        "self_uid" to (if (selfUid().isEmpty()) "" else selfUid().take(6) + "***"),
        "logged_in" to loggedIn(),
        "last_error" to lastError
    )
}
