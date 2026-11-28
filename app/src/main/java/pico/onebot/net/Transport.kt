package pico.onebot.net

import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.event.MetaEvents
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 所有活着的 OneBot 连接(正向 WS 接进来的 + 反向 WS 连出去的)。
 * 事件"序列化一次,多路广播"就发生在这里。
 */
object Transport {

    private const val REPLAY_CAPACITY = 256
    private const val REPLAY_MAX_AGE_NS = 60_000_000_000L
    /** 首个消费者重连回放后保留短时间窗,避免多消费者同时重连时后连上的被饿死。 */
    private const val REPLAY_MULTI_CONSUMER_GRACE_NS = 10_000_000_000L
    private const val MAX_TRACKED_REPLAY_CURSORS = 64

    private data class BufferedEvent(
        val seq: Long,
        val text: String,
        val tsNanos: Long,
        var firstReplayedAtNanos: Long = 0L
    )

    private val conns = CopyOnWriteArrayList<WsConn>()
    private val lock = Any()
    private val replayBuffer = ArrayDeque<BufferedEvent>(REPLAY_CAPACITY)
    private val lastReplayedSeqByTag = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var replaySeq: Long = 0L
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
                val connectEvent = MetaEvents.lifecycleEvent("connect").toString()
                c.sendText(connectEvent)
                if (!c.closed && replayBuffer.isNotEmpty()) {
                    replayLocked(c)
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
        broadcast(text, isMetaEvent(text))
    }

    /**
     * 广播一条已序列化的事件文本。
     * @return 是否至少投递给了一个可接收事件的 WS 消费者。
     */
    fun broadcast(text: String, isMeta: Boolean): Boolean {
        synchronized(lock) {
            val now = System.nanoTime()
            if (replayBuffer.isNotEmpty()) pruneExpiredLocked(now)
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
            if (!delivered && Config.enabled && !isMeta) {
                enqueueReplayLocked(text, now)
            }
            return delivered
        }
    }

    private fun enqueueReplayLocked(text: String, now: Long) {
        pruneExpiredLocked(now)
        while (replayBuffer.size >= REPLAY_CAPACITY) {
            replayBuffer.removeFirst()
        }
        replayBuffer.addLast(BufferedEvent(++replaySeq, text, now))
    }

    /**
     * 非破坏性回放:不立刻把缓冲条目弹空,让在短窗内相继重连的多个 WS 消费者都能收到;
     * 对具备稳定标识的反向 WS 连接按序号去重,避免抖动重连重复投递。
     */
    private fun replayLocked(c: WsConn) {
        val now = System.nanoTime()
        pruneExpiredLocked(now)
        if (replayBuffer.isEmpty()) return
        val stableTag = if (c.tag.startsWith("rws:")) c.tag else null
        val prevSeq = if (stableTag != null) (lastReplayedSeqByTag[stableTag] ?: 0L) else 0L
        var maxSentSeq = prevSeq
        var replayed = 0
        for (ev in replayBuffer) {
            if (ev.seq <= prevSeq) continue
            if (!c.sendText(ev.text)) break
            if (ev.firstReplayedAtNanos == 0L) {
                ev.firstReplayedAtNanos = now
            }
            maxSentSeq = ev.seq
            replayed++
        }
        if (stableTag != null && maxSentSeq > prevSeq) {
            lastReplayedSeqByTag[stableTag] = maxSentSeq
            while (lastReplayedSeqByTag.size > MAX_TRACKED_REPLAY_CURSORS) {
                val it = lastReplayedSeqByTag.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
        if (replayed > 0) {
            PicoLog.i("replayed $replayed buffered event(s) to " + c.tag)
        }
    }

    private fun pruneExpiredLocked(nowNanos: Long) {
        val it = replayBuffer.iterator()
        while (it.hasNext()) {
            val ev = it.next()
            val tooOld = (nowNanos - ev.tsNanos) > REPLAY_MAX_AGE_NS
            val graceExpired = ev.firstReplayedAtNanos != 0L &&
                (nowNanos - ev.firstReplayedAtNanos) > REPLAY_MULTI_CONSUMER_GRACE_NS
            if (tooOld || graceExpired) {
                it.remove()
            }
        }
    }

    /**
     * 轻量扫描顶层 JSON 的 `post_type` 字段,避免为了判断是否为 meta_event
     * 而完整构造整棵 `JSONObject` DOM。
     */
    internal fun isMetaEvent(text: String): Boolean {
        if (!text.contains("\"meta_event\"")) return false
        var i = 0
        val n = text.length
        var depth = 0
        while (i < n) {
            when (text[i]) {
                '{' -> {
                    depth++
                    i++
                }
                '}' -> {
                    depth--
                    i++
                }
                '"' -> {
                    val keyStart = i + 1
                    val keyEnd = scanJsonStringEnd(text, i)
                    if (keyEnd < 0) return false
                    if (depth == 1 && regionMatchesKey(text, keyStart, keyEnd, "post_type")) {
                        var j = skipWs(text, keyEnd + 1)
                        if (j < n && text[j] == ':') {
                            j = skipWs(text, j + 1)
                            if (j < n && text[j] == '"') {
                                val valEnd = scanJsonStringEnd(text, j)
                                if (valEnd < 0) return false
                                return regionMatchesKey(text, j + 1, valEnd, "meta_event")
                            }
                            return false
                        }
                    }
                    i = keyEnd + 1
                }
                else -> i++
            }
        }
        return false
    }

    private fun scanJsonStringEnd(s: String, openQuoteIdx: Int): Int {
        var i = openQuoteIdx + 1
        val n = s.length
        while (i < n) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun skipWs(s: String, start: Int): Int {
        var i = start
        val n = s.length
        while (i < n) {
            when (s[i]) {
                ' ', '\t', '\r', '\n' -> i++
                else -> return i
            }
        }
        return n
    }

    private fun regionMatchesKey(s: String, start: Int, end: Int, expected: String): Boolean {
        if (end - start != expected.length) return false
        return s.regionMatches(start, expected, 0, expected.length)
    }

    fun count(): Int = conns.size

    /** 当前存活且订阅事件(`/event` 或 `/`)的 WS 消费者数量(不含纯 `/api` 连接)。 */
    fun eventConsumerCount(): Int = conns.count { !it.closed && it.wantsEvents }

    /** 某个网络配置项当前真正挂着的对端数量(WebUI 的在线灯只认这个,不认 enabled)。 */
    fun peers(ownerId: String): Int =
        if (ownerId.isEmpty()) 0 else conns.count { !it.closed && it.ownerId == ownerId }

    fun describe(): List<String> = conns.map {
        it.tag + "(" + it.role + (if (it.overflowCount() > 0) ", overflow=" + it.overflowCount() else "") + ")"
    }

    /** 会话失效或切号时清空重连回放缓冲与游标,防止重连 WS 收到旧账号事件。 */
    fun clearReplay() {
        synchronized(lock) {
            replayBuffer.clear()
            lastReplayedSeqByTag.clear()
        }
    }

    fun closeAll() {
        val snapshot: List<WsConn>
        synchronized(lock) {
            epoch++
            snapshot = ArrayList(conns)
            conns.clear()
            if (!Config.enabled) {
                replayBuffer.clear()
                lastReplayedSeqByTag.clear()
            }
        }
        for (c in snapshot) {
            c.close()
        }
    }
}
