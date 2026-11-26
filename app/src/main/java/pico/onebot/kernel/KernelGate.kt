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
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 与宿主耦合的唯一入口之一(另一个是 hook 包)。
 *
 * 会话解析刻意**不写死混淆过的方法名**:`KernelServiceUtil.g()` 这种单字母名会随
 * base APK 版本漂移,但它的**返回类型** `IQQNTWrapperSession` 来自不混淆的
 * `nativeinterface` 包 —— 所以按返回类型反射查找,换版本不必改代码。
 */
object KernelGate {

    enum class State { INIT, TRANSPORT_UP, SESSION_READY, LOGGED_IN, ONLINE, LOGGED_OUT }

    private const val LOGOUT_SUPPRESS_NS = 5_000_000_000L

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

    private val watchWakeup = Semaphore(0)
    private val watchStarted = AtomicBoolean(false)

    @Volatile
    private var watchThread: Thread? = null

    @Volatile
    private var cachedSessionMethod: java.lang.reflect.Method? = null

    @Volatile
    private var cachedSessionField: java.lang.reflect.Field? = null

    @Volatile
    private var cachedCandidateMethods: List<java.lang.reflect.Method> = emptyList()

    @Volatile
    private var cachedCandidateFields: List<java.lang.reflect.Field> = emptyList()

    @Volatile
    private var candidatesCached: Boolean = false

    /**
     * 会话代数。每次 [onSessionLost] 在锁内自增,用于让正在执行的 [probe] 与监听器注册立刻作废。
     */
    @Volatile
    private var sessionEpoch: Long = 0

    @Volatile
    private var loggingOut: Boolean = false

    private var logoutSuppressUntilNs: Long = 0L
    private var hasLogoutSuppress: Boolean = false
    private var logoutSuppressedSession: IQQNTWrapperSession? = null

    private val pendingTransitions = ArrayDeque<Pair<State, State>>()
    private var drainingTransitions = false

    private const val KERNEL_SERVICE_UTIL = "com.tencent.qqnt.msg.KernelServiceUtil"

    // ---------------- 状态机与清理钩子 ----------------

    private val stateListeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val sessionLostHooks = CopyOnWriteArrayList<() -> Unit>()

    /** 订阅状态机变化(引导面板等 UI 用);返回取消函数。回调线程不定,UI 自己 post。 */
    fun addStateListener(l: (State) -> Unit): () -> Unit {
        stateListeners.add(l)
        return { stateListeners.remove(l) }
    }

    /** 注册会话丢失/切号时的清理回调(线程安全);返回取消函数。 */
    fun registerSessionLostHook(cb: () -> Unit): () -> Unit {
        sessionLostHooks.add(cb)
        return { sessionLostHooks.remove(cb) }
    }

    fun currentEpoch(): Long = sessionEpoch

    fun isEpochCurrent(epoch: Long): Boolean = synchronized(this) {
        sessionEpoch == epoch && !loggingOut && state != State.LOGGED_OUT
    }

    fun setState(next: State) {
        val changed = synchronized(this) { transitionLocked(next) }
        if (changed) drainStateTransitions()
    }

    private fun transitionLocked(next: State): Boolean {
        if (state == next) return false
        val from = state
        state = next
        pendingTransitions.addLast(from to next)
        return true
    }

    private fun drainStateTransitions() {
        var cur: Pair<State, State>? = synchronized(this) {
            if (drainingTransitions) return
            val head = pendingTransitions.pollFirst() ?: return
            drainingTransitions = true
            head
        }
        try {
            while (cur != null) {
                notifyStateChanged(cur.first, cur.second)
                cur = synchronized(this) {
                    val next = pendingTransitions.pollFirst()
                    if (next == null) drainingTransitions = false
                    next
                }
            }
        } catch (t: Throwable) {
            synchronized(this) { drainingTransitions = false }
            throw t
        }
    }

    private fun notifyStateChanged(from: State, next: State) {
        try {
            MetaEvents.stateChanged(from.name, next.name)
        } catch (t: Throwable) {
            PicoLog.w("MetaEvents.stateChanged failed: " + t.message)
        }
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
        if (!watchStarted.compareAndSet(false, true)) return
        try {
            Thread({ watchLoop() }, "pico-kernel-watch").apply { isDaemon = true }.start()
        } catch (t: Throwable) {
            watchStarted.set(false)
            throw t
        }
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
                watchWakeup.tryAcquire(delay, TimeUnit.MILLISECONDS)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** 一次探测:会话 → 登录态 → 监听器注册。每步都可独立失败并在下一轮重试。 */
    private fun probe() {
        val epochAtStart: Long
        val cached: IQQNTWrapperSession?
        val suppressActive: Boolean
        val suppressUntil: Long
        val suppressedSession: IQQNTWrapperSession?
        synchronized(this) {
            if (loggingOut) return
            epochAtStart = sessionEpoch
            cached = sessionRef
            suppressActive = hasLogoutSuppress
            suppressUntil = logoutSuppressUntilNs
            suppressedSession = logoutSuppressedSession
        }

        val isLoggedIn = loggedIn()
        val live = findSessionByReturnType()

        // api_logout 后等待宿主主线程 app.logout(true) 真正清除 isLogin,避免立刻重挂回旧会话
        if (suppressActive) {
            if (!isLoggedIn || live == null || (suppressedSession != null && live !== suppressedSession)) {
                synchronized(this) {
                    if (sessionEpoch == epochAtStart) {
                        hasLogoutSuppress = false
                        logoutSuppressUntilNs = 0L
                        logoutSuppressedSession = null
                    }
                }
            } else {
                if ((suppressUntil - System.nanoTime()) > 0L) {
                    return
                }
                synchronized(this) {
                    if (sessionEpoch == epochAtStart) {
                        hasLogoutSuppress = false
                        logoutSuppressUntilNs = 0L
                        logoutSuppressedSession = null
                    }
                }
            }
        }

        if (live == null || !isLoggedIn) {
            val shouldLose: Boolean
            val shouldTransportUp: Boolean
            synchronized(this) {
                if (sessionEpoch != epochAtStart || loggingOut) return
                shouldLose = state == State.ONLINE || state == State.LOGGED_IN ||
                    state == State.SESSION_READY || sessionRef != null || listenerId != 0L
                shouldTransportUp = !shouldLose && state == State.INIT
                if (shouldTransportUp) transitionLocked(State.TRANSPORT_UP)
            }
            if (shouldLose) {
                onSessionLost(if (live == null) "no_session" else "not_logged_in")
            } else if (shouldTransportUp) {
                drainStateTransitions()
            }
            return
        }

        if (cached != null && cached !== live) {
            onSessionLost("session_replaced")
            // 下一轮探测在新 epoch 上接管 live
            return
        }

        val transitionedToReady = synchronized(this) {
            if (sessionEpoch != epochAtStart || loggingOut) return
            if (sessionRef !== live) sessionRef = live
            if (state == State.INIT || state == State.TRANSPORT_UP || state == State.LOGGED_OUT) {
                transitionLocked(State.SESSION_READY)
            } else {
                false
            }
        }
        if (transitionedToReady) drainStateTransitions()

        val uin = readSelfUin()
        if (uin <= 0L) return
        val uid = readSelfUid()

        val needRegisterListener: Boolean
        val transitionedAfterLogin: Boolean
        synchronized(this) {
            if (sessionEpoch != epochAtStart || loggingOut || sessionRef !== live) return
            selfUinCache = uin
            if (uid.isNotEmpty()) selfUidCache = uid
            var changed = false
            if (state == State.SESSION_READY) {
                changed = transitionLocked(State.LOGGED_IN) || changed
            }
            needRegisterListener = (listenerId == 0L)
            if (!needRegisterListener && state == State.LOGGED_IN) {
                changed = transitionLocked(State.ONLINE) || changed
            }
            transitionedAfterLogin = changed
        }
        if (transitionedAfterLogin) drainStateTransitions()

        if (needRegisterListener) {
            val msg = safe { live.msgService } ?: return
            val id = MsgListenerProxy.register(msg)
            if (id != 0L) {
                val committed = synchronized(this) {
                    if (sessionEpoch != epochAtStart || loggingOut || sessionRef !== live || listenerId != 0L) {
                        false
                    } else {
                        listenerId = id
                        transitionLocked(State.ONLINE)
                        true
                    }
                }
                if (!committed) {
                    PicoLog.w("probe: session lost during listener registration (id=$id), discarding")
                    runCatching { live.msgService?.removeKernelMsgListener(id) }
                        .onFailure { PicoLog.d("discard listener($id) ignored: ${it.message}") }
                    return
                }
                PicoLog.i("kernel msg listener registered, id=$id")
                drainStateTransitions()
            }
        }

        // 名册监听器单独注册:它失败不该拖垮消息通路,传入 session 与 epoch 做代际守护
        val shouldRegisterContacts = synchronized(this) {
            sessionEpoch == epochAtStart && !loggingOut && state == State.ONLINE && !ContactListeners.registered
        }
        if (shouldRegisterContacts) {
            ContactListeners.register(live, epochAtStart)
        }
    }

    /** 被踢/会话失效时回落,下一轮探测会重新注册监听器。 */
    fun onSessionLost(reason: String) {
        val oldId: Long
        val oldSession: IQQNTWrapperSession?
        val transitioned: Boolean
        synchronized(this) {
            if (listenerId == 0L && sessionRef == null && state == State.LOGGED_OUT && reason != "api_logout") return
            loggingOut = true
            oldId = listenerId
            oldSession = sessionRef
            listenerId = 0
            sessionRef = null
            selfUinCache = 0
            selfUidCache = ""
            // 让正在进行中的 probe 与监听器注册立刻作废
            sessionEpoch++
            if (reason == "api_logout") {
                hasLogoutSuppress = true
                logoutSuppressUntilNs = System.nanoTime() + LOGOUT_SUPPRESS_NS
                logoutSuppressedSession = oldSession
            }
            transitioned = transitionLocked(State.LOGGED_OUT)
        }
        try {
            PicoLog.w("session lost: $reason")
            if (oldId != 0L && oldSession != null) {
                runCatching { oldSession.msgService?.removeKernelMsgListener(oldId) }
                    .onFailure { PicoLog.d("removeKernelMsgListener($oldId) ignored: ${it.message}") }
            }
            ContactListeners.reset(oldSession)
            // 名册/昵称/消息管道/发送回执均是当前账号视角,切号必须统一清空
            ContactStore.reset()
            NickStore.reset()
            MsgPipeline.reset()
            SendTracker.reset()
            ReadReporter.reset()
            MsgWatchdog.reset()
            pico.onebot.media.MediaRefStore.reset()
            pico.onebot.msg.ForwardStore.reset()
            pico.onebot.media.MediaDownloader.reset()
            pico.onebot.media.MediaFiles.clearServed()
            for (hook in sessionLostHooks) {
                try {
                    hook()
                } catch (t: Throwable) {
                    PicoLog.w("session lost hook failed: " + t.message)
                }
            }
            if (transitioned) drainStateTransitions()
        } finally {
            synchronized(this) {
                loggingOut = false
            }
        }
        wakeWatch()
    }

    // ---------------- 会话与服务 ----------------

    fun session(): IQQNTWrapperSession? {
        if (loggingOut || state == State.LOGGED_OUT) return null
        sessionRef?.let { return it }
        if (!loggedIn()) return null
        val epochAtStart = sessionEpoch
        val found = findSessionByReturnType() ?: return null
        synchronized(this) {
            if (loggingOut || state == State.LOGGED_OUT || sessionEpoch != epochAtStart) return null
            if (sessionRef == null) sessionRef = found
            return sessionRef
        }
    }

    /**
     * 会话解析结果的可读说明。登录前拿不到会话是**正常**的,但"为什么拿不到"
     * 必须说得出来(类没了 / 没有这种 getter / getter 返回 null),否则排查就是瞎猜。
     */
    @Volatile
    var sessionProbe: String = "not-probed"
        private set

    private fun findSessionByReturnType(): IQQNTWrapperSession? {
        cachedSessionMethod?.let { m ->
            try {
                val v = m.invoke(null) as? IQQNTWrapperSession
                sessionProbe = if (v != null) {
                    "ok:method " + m.name + "()"
                } else {
                    "getter-returned-null(" + m.name + "(),登录前属正常)"
                }
                return v
            } catch (_: Throwable) {
                cachedSessionMethod = null
            }
        }
        cachedSessionField?.let { f ->
            try {
                val v = f.get(null) as? IQQNTWrapperSession
                sessionProbe = if (v != null) {
                    "ok:field " + f.name
                } else {
                    "getter-returned-null(" + f.name + ",登录前属正常)"
                }
                return v
            } catch (_: Throwable) {
                cachedSessionField = null
            }
        }
        if (candidatesCached) {
            val methodsSnap = cachedCandidateMethods
            val fieldsSnap = cachedCandidateFields
            if (methodsSnap.isNotEmpty() || fieldsSnap.isNotEmpty()) {
                var okInvocations = 0
                var lastThrow: String? = null
                for (m in methodsSnap) {
                    try {
                        val v = m.invoke(null) as? IQQNTWrapperSession
                        okInvocations++
                        if (v != null) {
                            cachedSessionMethod = m
                            sessionProbe = "ok:method " + m.name + "()"
                            return v
                        }
                    } catch (t: Throwable) {
                        lastThrow = m.name + "(): " + t.javaClass.simpleName + " " + t.message
                    }
                }
                for (f in fieldsSnap) {
                    try {
                        val v = f.get(null) as? IQQNTWrapperSession
                        okInvocations++
                        if (v != null) {
                            cachedSessionField = f
                            sessionProbe = "ok:field " + f.name
                            return v
                        }
                    } catch (t: Throwable) {
                        lastThrow = f.name + ": " + t.javaClass.simpleName + " " + t.message
                    }
                }
                if (okInvocations > 0) {
                    sessionProbe = "getter-returned-null(已缓存候选 ${methodsSnap.size + fieldsSnap.size} 个,登录前属正常)"
                    return null
                }
                if (lastThrow != null) {
                    candidatesCached = false
                    cachedCandidateMethods = emptyList()
                    cachedCandidateFields = emptyList()
                }
            }
        }
        val cls = try {
            Class.forName(KERNEL_SERVICE_UTIL)
        } catch (_: Throwable) {
            sessionProbe = "class-missing:$KERNEL_SERVICE_UTIL(base APK 变了?)"
            return null
        }
        val discoveredMethods = ArrayList<java.lang.reflect.Method>()
        val discoveredFields = ArrayList<java.lang.reflect.Field>()
        var lastThrow: String? = null

        val methods = try {
            cls.declaredMethods
        } catch (t: Throwable) {
            lastError = "findSession: " + t.javaClass.simpleName + " " + t.message
            sessionProbe = "throw:" + lastError
            return null
        }
        for (m in methods) {
            try {
                if (!Modifier.isStatic(m.modifiers)) continue
                if (m.parameterTypes.isNotEmpty()) continue
                if (!IQQNTWrapperSession::class.java.isAssignableFrom(m.returnType)) continue
                m.isAccessible = true
                discoveredMethods.add(m)
                val v = m.invoke(null) as? IQQNTWrapperSession
                if (v != null) {
                    cachedSessionMethod = m
                    cachedCandidateMethods = ArrayList(discoveredMethods)
                    candidatesCached = true
                    sessionProbe = "ok:method " + m.name + "()"
                    return v
                }
            } catch (t: Throwable) {
                lastThrow = m.name + "(): " + t.javaClass.simpleName + " " + t.message
            }
        }

        // 兜底:有些版本把会话放在静态字段上
        val fields = try {
            cls.declaredFields
        } catch (_: Throwable) {
            emptyArray()
        }
        for (f in fields) {
            try {
                if (!Modifier.isStatic(f.modifiers)) continue
                if (!IQQNTWrapperSession::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                discoveredFields.add(f)
                val v = f.get(null) as? IQQNTWrapperSession
                if (v != null) {
                    cachedSessionField = f
                    cachedCandidateMethods = ArrayList(discoveredMethods)
                    cachedCandidateFields = ArrayList(discoveredFields)
                    candidatesCached = true
                    sessionProbe = "ok:field " + f.name
                    return v
                }
            } catch (t: Throwable) {
                lastThrow = f.name + ": " + t.javaClass.simpleName + " " + t.message
            }
        }

        val candidates = discoveredMethods.size + discoveredFields.size
        if (candidates > 0) {
            cachedCandidateMethods = discoveredMethods
            cachedCandidateFields = discoveredFields
            candidatesCached = true
            if (candidates == 1 && lastThrow == null) {
                if (discoveredMethods.size == 1) cachedSessionMethod = discoveredMethods[0]
                else if (discoveredFields.size == 1) cachedSessionField = discoveredFields[0]
            }
        }

        sessionProbe = when {
            candidates == 0 ->
                "no-static-getter-returning-IQQNTWrapperSession(共 " + methods.size + " 个方法)"
            lastThrow != null -> {
                lastError = "findSession: $lastThrow"
                "throw:$lastError"
            }
            else ->
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
        if (loggingOut || state == State.LOGGED_OUT) return 0L
        val cached = selfUinCache
        if (cached > 0L) return cached
        val epochAtStart = sessionEpoch
        val v = readSelfUin()
        if (v <= 0L) return 0L
        synchronized(this) {
            if (loggingOut || state == State.LOGGED_OUT || sessionEpoch != epochAtStart) return 0L
            selfUinCache = v
            return v
        }
    }

    fun selfUid(): String {
        if (loggingOut || state == State.LOGGED_OUT) return ""
        val cached = selfUidCache
        if (cached.isNotEmpty()) return cached
        val epochAtStart = sessionEpoch
        val v = readSelfUid()
        if (v.isEmpty()) return ""
        synchronized(this) {
            if (loggingOut || state == State.LOGGED_OUT || sessionEpoch != epochAtStart) return ""
            selfUidCache = v
            return v
        }
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
