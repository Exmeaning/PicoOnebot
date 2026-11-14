package pico.onebot.net

import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 所有活着的 OneBot 连接(正向 WS 接进来的 + 反向 WS 连出去的)。
 * 事件"序列化一次,多路广播"就发生在这里。
 */
object Transport {

    private const val REPLAY_CAPACITY = 256
    private const val REPLAY_MAX_AGE_MS = 60_000L

    private data class BufferedEvent(val text: String, val ts: Long)

    private val conns = CopyOnWriteArrayList<WsConn>()
    private val lock = Any()
    private val replayBuffer = ArrayDeque<BufferedEvent>(REPLAY_CAPACITY)

    fun add(c: WsConn) {
        synchronized(lock) {
            conns.add(c)
            PicoLog.i("ws connected: " + c.tag + " role=" + c.role + " total=" + conns.size)
            if (c.wantsEvents && Config.enabled && !c.closed && replayBuffer.isNotEmpty()) {
                drainReplayLocked(c)
            }
        }
    }

    fun remove(c: WsConn) {
        if (conns.remove(c)) {
            PicoLog.i("ws closed: " + c.tag + " total=" + conns.size)
        }
    }

    fun broadcast(text: String) {
        synchronized(lock) {
            var delivered = false
            for (c in conns) {
                if (c.closed) {
                    remove(c)
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
        val now = System.currentTimeMillis()
        pruneExpiredLocked(now)
        while (replayBuffer.size >= REPLAY_CAPACITY) {
            replayBuffer.removeFirst()
        }
        replayBuffer.addLast(BufferedEvent(text, now))
    }

    private fun drainReplayLocked(c: WsConn) {
        val now = System.currentTimeMillis()
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

    private fun pruneExpiredLocked(now: Long) {
        while (replayBuffer.isNotEmpty()) {
            val head = replayBuffer.peekFirst()
            if (now - head.ts > REPLAY_MAX_AGE_MS) {
                replayBuffer.removeFirst()
            } else {
                break
            }
        }
    }

    private fun isMetaEvent(text: String): Boolean = try {
        JSONObject(text).optString("post_type") == "meta_event"
    } catch (_: Throwable) {
        false
    }

    fun count(): Int = conns.size

    /** 某个网络配置项当前真正挂着的对端数量(WebUI 的在线灯只认这个,不认 enabled)。 */
    fun peers(ownerId: String): Int =
        if (ownerId.isEmpty()) 0 else conns.count { !it.closed && it.ownerId == ownerId }

    fun describe(): List<String> = conns.map { it.tag + "(" + it.role + ")" }

    fun closeAll() {
        for (c in conns) c.close()
        conns.clear()
        if (!Config.enabled) {
            synchronized(lock) {
                replayBuffer.clear()
            }
        }
    }
}
