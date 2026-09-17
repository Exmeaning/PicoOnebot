package pico.onebot.event

import org.json.JSONObject
import pico.onebot.action.SystemActions
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.proto.Ob

/** meta 事件:lifecycle(连接/状态变化)与 heartbeat。 */
object MetaEvents {

    @Volatile
    private var running = false

    /** 最近一次心跳的时间戳(ms),BotStatus.lastHeartbeat 用。 */
    @Volatile
    var lastHeartbeatAt: Long = 0
        private set

    fun start() {
        if (running) return
        running = true
        lifecycle("connect")
        Thread({ heartbeatLoop() }, "pico-heartbeat").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
    }

    fun lifecycle(subType: String) {
        EventBus.post(
            Ob.event("meta_event")
                .put("meta_event_type", "lifecycle")
                .put("sub_type", subType)
        )
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
        if (to == "ONLINE") lifecycle("enable")
    }

    private fun heartbeatLoop() {
        while (running) {
            val interval = Config.heartbeatInterval
            try {
                Thread.sleep(interval)
            } catch (t: InterruptedException) {
                return
            }
            if (!running) return
            lastHeartbeatAt = System.currentTimeMillis()
            EventBus.post(
                Ob.event("meta_event")
                    .put("meta_event_type", "heartbeat")
                    .put("status", SystemActions.statusObject())
                    .put("interval", interval)
            )
        }
    }
}
