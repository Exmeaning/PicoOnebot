package pico.onebot.net

import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.proto.Ob
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 所有活着的 OneBot 连接(正向 WS 接进来的 + 反向 WS 连出去的)。
 * 事件"序列化一次,多路广播"就发生在这里。
 */
object Transport {

    private const val REPLAY_CAPACITY = 256
    private const val REPLAY_MAX_AGE_NS = 60_000_000_000L

    private data class BufferedEvent(val text: String, val tsNanos: Long)

    private val conns = CopyOnWriteArrayList<WsConn>()
    private val lock = Any()
    private val replayBuffer = ArrayDeque<BufferedEvent>(REPLAY_CAPACITY)
    private var epoch: Long = 0L

    fun currentEpoch(): Long = synchronized(lock) { epoch }

    fun add(c: WsConn) {
        add(c, -1L)
    }

    /**
     * 登记一条新的 OneBot WebSocket 连接。
     * 若连接已关闭或热重载代际已过期,拒绝登记并确保返回 false。
     */
    fun add(c: WsConn, expectedEpoch: Long): Boolean {
        synchronized(lock) {
            if (c.closed) return false
            if (expectedEpoch >= 0L && expectedEpoch != epoch) return false
            conns.add(c)
            PicoLog.i("ws connected: " + c.tag + " role=" + c.role + " total=" + conns.size)
            if (c.wantsEvents && Config.enabled && !c.closed) {
                // OneBot v11: 每条新 WS 连接建立后推送一次 lifecycle.connect。
                val connectEvent = Ob.event("meta_event")
                    .put("meta_event_type", "lifecycle")
                    .put("sub_type", "connect")
                    .toString()
                c.sendText(connectEvent)
                if (!c.closed && replayBuffer.isNotEmpty()) {
                    drainReplayLocked(c)
                }
            }
            return true
        }
    }

    fun remove(c: WsConn) {
        synchronized(lock) {
            if (conns.remove(c)) {
                PicoLog.i("ws closed: " + c.tag + " total=" + conns.size)
            }
        }
    }

    fun broadcast(text: String) {
        synchronized(lock) {
            var delivered = false
            for (c in conns) {
                if (c.closed) {
                    if (conns.remove(c)) {
                        PicoLog.i("ws closed: " + c.tag + " total=" + conns.size)
                    }
                    continue
                }
                if (c.wantsEvents && c.sendText(text)) {
                    delivered = true
                }
            }
            if (!delivered && Config.enabled && !isMetaEvent(text)) {
                enqueueReplayLocked(text)
            }
        }
    }

    private fun enqueueReplayLocked(text: String) {
        val now = System.nanoTime()
        pruneExpiredLocked(now)
        while (replayBuffer.size >= REPLAY_CAPACITY) {
            replayBuffer.removeFirst()
        }
        replayBuffer.addLast(BufferedEvent(text, now))
    }

    private fun drainReplayLocked(c: WsConn) {
        val now = System.nanoTime()
        pruneExpiredLocked(now)
        var replayed = 0
        while (replayBuffer.isNotEmpty()) {
            val ev = replayBuffer.peekFirst()
            if (!c.sendText(ev.text)) break
            replayBuffer.removeFirst()
            replayed++
        }
        if (replayed > 0) {
            PicoLog.i("replayed $replayed buffered event(s) to " + c.tag)
        }
    }

    private fun pruneExpiredLocked(nowNanos: Long) {
        while (replayBuffer.isNotEmpty()) {
            val head = replayBuffer.peekFirst()
            if (nowNanos - head.tsNanos > REPLAY_MAX_AGE_NS) {
                replayBuffer.removeFirst()
            } else {
                break
            }
        }
    }

    private fun isMetaEvent(text: String): Boolean {
        if (!text.contains("\"meta_event\"")) return false
        return try {
            JSONObject(text).optString("post_type") == "meta_event"
        } catch (_: Throwable) {
            false
        }
    }

    fun count(): Int = conns.size

    /** 某个网络配置项当前真正挂着的对端数量(WebUI 的在线灯只认这个,不认 enabled)。 */
    fun peers(ownerId: String): Int =
        if (ownerId.isEmpty()) 0 else conns.count { !it.closed && it.ownerId == ownerId }

    fun describe(): List<String> = conns.map {
        it.tag + "(" + it.role + (if (it.overflowCount() > 0) ", overflow=" + it.overflowCount() else "") + ")"
    }

    fun closeAll() {
        val snapshot: List<WsConn>
        synchronized(lock) {
            epoch++
            snapshot = ArrayList(conns)
            conns.clear()
            if (!Config.enabled) {
                replayBuffer.clear()
            }
        }
        for (c in snapshot) {
            c.close()
        }
    }
}
