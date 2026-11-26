package pico.onebot.event

import org.json.JSONObject
import pico.onebot.action.SystemActions
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.proto.Ob

/** meta 事件:lifecycle(连接/状态变化)与 heartbeat。 */
object MetaEvents {

    private const val MIN_HEARTBEAT_INTERVAL_MS = 1000L

    @Volatile
    private var running = false

    @Volatile
    private var heartbeatThread: Thread? = null

    private val lock = Any()

    /** 最近一次心跳的时间戳(ms),BotStatus.lastHeartbeat 用。 */
    @Volatile
    var lastHeartbeatAt: Long = 0
        private set

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val t = Thread({ heartbeatLoop() }, "pico-heartbeat").apply { isDaemon = true }
            heartbeatThread = t
            t.start()
        }
        lifecycle("connect")
    }

    fun stop() {
        val t: Thread?
        synchronized(lock) {
            running = false
            t = heartbeatThread
            heartbeatThread = null
        }
        t?.interrupt()
    }

    fun lifecycleEvent(subType: String): JSONObject =
        Ob.event("meta_event")
            .put("meta_event_type", "lifecycle")
            .put("sub_type", subType)

    fun lifecycle(subType: String) {
        EventBus.post(lifecycleEvent(subType))
    }

    /** 自定义:状态机变化时广播一条,控制台/上游据此感知登录进度。 */
    fun stateChanged(from: String, to: String) {
        PicoLog.i("state: $from -> $to")
        EventBus.post(
            Ob.event("meta_event")
                .put("meta_event_type", "lifecycle")
                .put("sub_type", "pico.state")
                .put("pico", JSONObject().put("from", from).put("to", to))
        )
        if (to == "ONLINE" && from != "ONLINE") lifecycle("enable")
        else if (from == "ONLINE" && to != "ONLINE") lifecycle("disable")
    }

    private fun heartbeatLoop() {
        val me = Thread.currentThread()
        try {
            while (running && heartbeatThread === me) {
                val rawInterval = Config.heartbeatInterval
                if (rawInterval <= 0L) {
                    try {
                        Thread.sleep(5000L)
                    } catch (_: InterruptedException) {
                        return
                    }
                    continue
                }
                val interval = rawInterval.coerceAtLeast(MIN_HEARTBEAT_INTERVAL_MS)
                try {
                    Thread.sleep(interval)
                } catch (_: InterruptedException) {
                    return
                }
                if (!running || heartbeatThread !== me) return
                try {
                    lastHeartbeatAt = System.currentTimeMillis()
                    EventBus.post(
                        Ob.event("meta_event")
                            .put("meta_event_type", "heartbeat")
                            .put("status", SystemActions.statusObject())
                            .put("interval", interval)
                    )
                } catch (t: Throwable) {
                    PicoLog.w("heartbeat failed: " + t.message)
                }
            }
        } finally {
            synchronized(lock) {
                if (heartbeatThread === me) heartbeatThread = null
            }
        }
    }
}
