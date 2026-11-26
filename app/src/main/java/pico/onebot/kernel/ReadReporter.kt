package pico.onebot.kernel

import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernelpublic.nativeinterface.Contact
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.Counters
import pico.onebot.core.PicoLog
import pico.onebot.core.Promise
import java.util.ArrayDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 节流已读上报(P0)。
 *
 * 防风控与并发约束:
 *  - 按 `(chatType, peerUid)` 聚合,单会话达到 `readReportBatch`(默认 10)或距首批未读超过
 *    `readReportIntervalSec`(默认 60s)才合并上报 1 次;
 *  - 自动上报受全局最小间隔(`readReportMinGapMs`) + 每分钟滑窗上限(`readReportMaxPerMin`)约束;
 *    手工 `markReadNow` 使用独立限流窗口,不饿死自动上报;
 *  - 通过单调 `inboundSeq`/`reportedSeq` + `inFlightCount` 消除 `markReadNow` 与 `tick` 并发双重扣减;
 *  - 所有时长/冷却/窗口均基于单调时钟 `System.nanoTime()`,免疫墙钟回跳;
 *  - LRU 淘汰优先回收 `pending == 0` 的空闲会话,绝不把零未读会话提升为 MRU。
 */
object ReadReporter {

    private const val MAX_CONVERSATIONS = 512
    private const val RATE_WINDOW_NS = 60_000_000_000L
    private const val CALLBACK_TIMEOUT_MS = 10_000L

    private data class ConvKey(val chatType: Int, val peerUid: String)

    private class ConvState(
        var pending: Int = 0,
        var inboundSeq: Long = 0L,
        var reportedSeq: Long = 0L,
        var inFlightCount: Int = 0,
        var hasFirstPending: Boolean = false,
        var firstPendingAtNs: Long = 0L,
        var hasLastReport: Boolean = false,
        var lastReportAtNs: Long = 0L,
        var hasBackoff: Boolean = false,
        var backoffUntilNs: Long = 0L
    )

    private val lock = Any()
    // 插入序(非 access-order):防止读取/清零 pending==0 的会话时将其提升至 MRU 反而淘汰有未读的会话
    private val conversations = LinkedHashMap<ConvKey, ConvState>(64, 0.75f, false)
    private val autoSendTimestamps = ArrayDeque<Long>()
    private val manualSendTimestamps = ArrayDeque<Long>()
    private val wakeup = Semaphore(0)

    @Volatile
    private var running = false

    private var epoch: Long = 0L
    private var hasAutoSend: Boolean = false
    private var lastAutoSendAtNs: Long = 0L
    private var lastReportWallAt: Long = 0L

    fun start() {
        synchronized(this) {
            if (running) return
            running = true
        }
        Thread({ loop() }, "pico-read-report").apply { isDaemon = true }.start()
        PicoLog.i("read reporter started")
    }

    /** 收到其他人的入站消息时登记待上报计数(不含机器人自身发送的消息)。 */
    fun onInboundMessage(chatType: Int, peerUid: String) {
        if (chatType != 1 && chatType != 2) return
        if (peerUid.isEmpty() || !Config.autoMarkRead) return
        val nowNs = System.nanoTime()
        var shouldWake = false
        synchronized(lock) {
            val key = ConvKey(chatType, peerUid)
            val existing = conversations.remove(key)
            val st = existing ?: ConvState()
            conversations[key] = st
            st.inboundSeq++
            st.pending = (st.inboundSeq - st.reportedSeq).toInt().coerceAtLeast(0)
            if (!st.hasFirstPending) {
                st.hasFirstPending = true
                st.firstPendingAtNs = nowNs
            }
            if (st.pending >= Config.readReportBatch) shouldWake = true
            trimConversationsLocked()
        }
        if (shouldWake && wakeup.availablePermits() == 0) {
            wakeup.release()
        }
    }

    /** 优先淘汰零未读且无在途 RPC 的空闲条目;若全部有未读再淘汰最旧条目。 */
    private fun trimConversationsLocked() {
        if (conversations.size <= MAX_CONVERSATIONS) return
        val idleIt = conversations.entries.iterator()
        while (conversations.size > MAX_CONVERSATIONS && idleIt.hasNext()) {
            val st = idleIt.next().value
            if (st.pending == 0 && st.inFlightCount == 0) idleIt.remove()
        }
        val anyIt = conversations.entries.iterator()
        while (conversations.size > MAX_CONVERSATIONS && anyIt.hasNext()) {
            val st = anyIt.next().value
            if (st.inFlightCount == 0) anyIt.remove()
        }
    }

    /** 会话丢失/换号时清空所有会话待上报状态,避免把上一账号的会话打到新账号上。 */
    fun reset() {
        synchronized(lock) {
            epoch++
            conversations.clear()
        }
        PicoLog.d("read reporter reset")
    }

    /** 手工立即上报指定会话已读(供 `mark_msg_as_read` / `/api/pico/mark_read` 调用)。 */
    fun markReadNow(chatType: Int, peerUid: String): Pair<Boolean, String> {
        if (chatType != 1 && chatType != 2) return false to "unsupported chatType: $chatType"
        if (peerUid.isEmpty()) return false to "peerUid is empty"
        if (!KernelGate.ready) return false to "kernel not ready (${KernelGate.state})"
        val msgService = KernelGate.msgService() ?: return false to "msgService unavailable"

        val key = ConvKey(chatType, peerUid)
        val nowNs = System.nanoTime()
        val manualCap = maxOf(Config.readReportMaxPerMin, 30)
        val snapEpoch: Long
        val snapSeq: Long
        val hadEntry: Boolean
        synchronized(lock) {
            pruneWindowLocked(manualSendTimestamps, nowNs)
            if (manualSendTimestamps.size >= manualCap) {
                return false to "manual read report rate limit exceeded ($manualCap/min)"
            }
            manualSendTimestamps.addLast(nowNs)
            snapEpoch = epoch
            val st = conversations[key]
            if (st != null) {
                st.inFlightCount++
                snapSeq = st.inboundSeq
                hadEntry = true
            } else {
                snapSeq = 0L
                hadEntry = false
            }
        }

        val (ok, detail) = try {
            invokeSetMsgRead(msgService, chatType, peerUid)
        } catch (t: Throwable) {
            if (hadEntry) releaseInFlight(key, snapEpoch)
            throw t
        }

        val finishedAtNs = System.nanoTime()
        val finishedWall = System.currentTimeMillis()
        synchronized(lock) {
            if (epoch == snapEpoch) {
                val st = conversations[key]
                if (st != null) {
                    if (hadEntry && st.inFlightCount > 0) st.inFlightCount--
                    if (ok) {
                        if (snapSeq > st.reportedSeq) st.reportedSeq = snapSeq
                        st.pending = (st.inboundSeq - st.reportedSeq).toInt().coerceAtLeast(0)
                        st.hasLastReport = true
                        st.lastReportAtNs = finishedAtNs
                        st.hasFirstPending = st.pending > 0
                        st.firstPendingAtNs = if (st.pending > 0) finishedAtNs else 0L
                        st.hasBackoff = false
                        st.backoffUntilNs = 0L
                    }
                }
            }
            if (ok) lastReportWallAt = finishedWall
        }

        if (ok) {
            Counters.onReadReportSent()
            PicoLog.i("manual read report ok chat=$chatType peer=$peerUid")
        } else {
            Counters.onReadReportFailed()
            PicoLog.w("manual read report failed chat=$chatType peer=$peerUid $detail")
        }
        return ok to detail
    }

    private fun releaseInFlight(key: ConvKey, snapEpoch: Long) {
        synchronized(lock) {
            if (epoch == snapEpoch) {
                val st = conversations[key]
                if (st != null && st.inFlightCount > 0) st.inFlightCount--
            }
        }
    }

    private fun loop() {
        while (running) {
            try {
                wakeup.drainPermits()
                val sleepMs = tick()
                wakeup.tryAcquire(sleepMs.coerceIn(200L, 5000L), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                if (!running) return
            } catch (t: Throwable) {
                PicoLog.w("read reporter loop error", t)
                try {
                    Thread.sleep(2000L)
                } catch (_: InterruptedException) {
                    if (!running) return
                }
            }
        }
    }

    /**
     * 尝试派发一条到期的已读上报。
     * @return 下一次检查前建议等待的毫秒数。
     */
    private fun tick(): Long {
        if (!Config.autoMarkRead || KernelGate.state != KernelGate.State.ONLINE) return 2000L
        val msgService = KernelGate.msgService() ?: return 2000L

        val nowNs = System.nanoTime()
        val batch = Config.readReportBatch
        val intervalMs = Config.readReportIntervalMs
        val intervalNs = TimeUnit.MILLISECONDS.toNanos(intervalMs)
        val minGapMs = Config.readReportMinGapMs
        val maxPerMin = Config.readReportMaxPerMin

        var targetKey: ConvKey? = null
        var snapPending = 0
        var snapSeq = 0L
        var snapEpoch = 0L
        var rateWaitMs = 0L

        synchronized(lock) {
            pruneWindowLocked(autoSendTimestamps, nowNs)

            // 优先选 pending 最多且达到批次或超时阈值的非在途会话(避免热点群逼近 30 未读)
            var bestPending = 0
            var bestFirstElapsedNs = -1L
            for ((k, st) in conversations) {
                if (st.pending <= 0 || st.inFlightCount > 0) continue
                if (st.hasBackoff && (st.backoffUntilNs - nowNs) > 0L) continue
                val baseAtNs = when {
                    st.hasLastReport && st.hasFirstPending ->
                        if (st.lastReportAtNs - st.firstPendingAtNs >= 0L) st.lastReportAtNs else st.firstPendingAtNs
                    st.hasFirstPending -> st.firstPendingAtNs
                    st.hasLastReport -> st.lastReportAtNs
                    else -> nowNs
                }
                val elapsedMs = (nowNs - baseAtNs).coerceAtLeast(0L) / 1_000_000L
                val due = st.pending >= batch || (st.hasFirstPending && elapsedMs >= intervalMs)
                if (!due) continue
                val firstElapsedNs = if (st.hasFirstPending) (nowNs - st.firstPendingAtNs).coerceAtLeast(0L) else 0L
                if (st.pending > bestPending || (st.pending == bestPending && firstElapsedNs > bestFirstElapsedNs)) {
                    targetKey = k
                    bestPending = st.pending
                    bestFirstElapsedNs = firstElapsedNs
                }
            }
            val chosenKey = targetKey ?: return 1000L

            // 全局最小间隔 + 每分钟滑窗上限(仅统计自动上报)
            val gapElapsedMs = if (hasAutoSend) (nowNs - lastAutoSendAtNs).coerceAtLeast(0L) / 1_000_000L else minGapMs
            val gapRemain = minGapMs - gapElapsedMs
            if (hasAutoSend && gapRemain > 0L) {
                rateWaitMs = gapRemain
            } else if (autoSendTimestamps.size >= maxPerMin) {
                val oldestNs = autoSendTimestamps.peekFirst() ?: nowNs
                val remainMs = ((oldestNs + RATE_WINDOW_NS) - nowNs) / 1_000_000L
                rateWaitMs = remainMs.coerceAtLeast(500L)
            } else {
                val st = conversations[chosenKey] ?: return 1000L
                st.inFlightCount++
                snapPending = st.pending
                snapSeq = st.inboundSeq
                snapEpoch = epoch
                recordAutoAttemptLocked(nowNs)
            }
        }

        if (rateWaitMs > 0L) return rateWaitMs
        val key = targetKey ?: return 1000L

        val (ok, detail) = try {
            invokeSetMsgRead(msgService, key.chatType, key.peerUid)
        } catch (t: Throwable) {
            releaseInFlight(key, snapEpoch)
            throw t
        }

        val finishedAtNs = System.nanoTime()
        val finishedWall = System.currentTimeMillis()
        if (ok) {
            synchronized(lock) {
                if (epoch == snapEpoch) {
                    val st = conversations[key]
                    if (st != null) {
                        if (st.inFlightCount > 0) st.inFlightCount--
                        if (snapSeq > st.reportedSeq) st.reportedSeq = snapSeq
                        st.pending = (st.inboundSeq - st.reportedSeq).toInt().coerceAtLeast(0)
                        st.hasLastReport = true
                        st.lastReportAtNs = finishedAtNs
                        st.hasFirstPending = st.pending > 0
                        st.firstPendingAtNs = if (st.pending > 0) finishedAtNs else 0L
                        st.hasBackoff = false
                        st.backoffUntilNs = 0L
                    }
                }
                lastReportWallAt = finishedWall
            }
            Counters.onReadReportSent()
            PicoLog.d("read report ok chat=${key.chatType} peer=${key.peerUid} cleared=$snapPending")
        } else {
            synchronized(lock) {
                if (epoch == snapEpoch) {
                    val st = conversations[key]
                    if (st != null) {
                        if (st.inFlightCount > 0) st.inFlightCount--
                        // 失败时强制退避一个周期(即使 pending>=batch),避免热点会话反复打 RPC
                        st.hasLastReport = true
                        st.lastReportAtNs = finishedAtNs
                        st.hasFirstPending = st.pending > 0
                        st.firstPendingAtNs = finishedAtNs
                        st.hasBackoff = true
                        st.backoffUntilNs = finishedAtNs + intervalNs
                    }
                }
            }
            Counters.onReadReportFailed()
            PicoLog.w("read report failed chat=${key.chatType} peer=${key.peerUid} $detail")
        }
        return minGapMs
    }

    private fun invokeSetMsgRead(
        msgService: com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService,
        chatType: Int,
        peerUid: String
    ): Pair<Boolean, String> {
        val promise = Promise<Pair<Int, String>>()
        try {
            // 必须调用 setMsgRead(is_local=0),绝不可调用 setLocalMsgRead(is_local=1 不发 SsoReadedReport)
            msgService.setMsgRead(
                Contact(chatType, peerUid, ""),
                IOperateCallback { code, msg ->
                    promise.resolve(code to (msg ?: ""))
                }
            )
        } catch (t: Throwable) {
            return false to ("exception: " + t.javaClass.simpleName + " " + t.message)
        }
        val timeout = minOf(Config.kernelTimeoutMs, CALLBACK_TIMEOUT_MS).coerceAtLeast(3000L)
        val res = promise.await(timeout) ?: return false to "timeout(${timeout}ms)"
        return if (res.first == 0) true to "ok" else false to "code=${res.first} ${res.second}"
    }

    private fun pruneWindowLocked(window: ArrayDeque<Long>, nowNs: Long) {
        while (window.isNotEmpty()) {
            val headNs = window.peekFirst() ?: break
            if ((nowNs - headNs).coerceAtLeast(0L) >= RATE_WINDOW_NS) window.removeFirst() else break
        }
    }

    private fun recordAutoAttemptLocked(nowNs: Long) {
        pruneWindowLocked(autoSendTimestamps, nowNs)
        hasAutoSend = true
        lastAutoSendAtNs = nowNs
        autoSendTimestamps.addLast(nowNs)
    }

    fun stats(): JSONObject {
        val nowNs = System.nanoTime()
        var pendingConvs = 0
        var pendingMsgs = 0
        var tracked = 0
        var windowCount = 0
        var lastSend = 0L
        synchronized(lock) {
            pruneWindowLocked(autoSendTimestamps, nowNs)
            tracked = conversations.size
            windowCount = autoSendTimestamps.size
            lastSend = lastReportWallAt
            for (st in conversations.values) {
                if (st.pending > 0) {
                    pendingConvs++
                    pendingMsgs += st.pending
                }
            }
        }
        return JSONObject()
            .put("enabled", Config.autoMarkRead)
            .put("batch", Config.readReportBatch)
            .put("interval_sec", Config.readReportIntervalMs / 1000L)
            .put("min_gap_ms", Config.readReportMinGapMs)
            .put("max_per_min", Config.readReportMaxPerMin)
            .put("sent", Counters.readReportsSent())
            .put("failed", Counters.readReportsFailed())
            .put("window_used", windowCount)
            .put("pending_conversations", pendingConvs)
            .put("pending_messages", pendingMsgs)
            .put("tracked_conversations", tracked)
            .put("last_report_at", lastSend)
    }
}
