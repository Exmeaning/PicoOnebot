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
 * 实证背景:Watch QQ 9.0.7 上对免打扰群(shield_flag=4),若长期不向服务端上报已读,
 * 服务端未读数累积超过阈值(如 30 条)后会停止下推 `OlPushService.MsgPush`。
 * `IKernelMsgService.setMsgRead(Contact, IOperateCallback)` 在原生层对应 `is_local=0`,
 * 会触发 `SendReadReport` 发送 `trpc.msg.msg_svc.MsgService.SsoReadedReport` 推进服务端
 * `read_msg_seq`(不可使用 `setLocalMsgRead`,后者 `is_local=1` 不发网络包)。
 *
 * 防风控约束:
 *  - 按 `(chatType, peerUid)` 聚合,单会话达到 `readReportBatch`(默认 10)或距首批未读超过
 *    `readReportIntervalSec`(默认 60s)才合并上报 1 次;
 *  - 全局强制最小间隔(`readReportMinGapMs`,默认 1500ms) + 每分钟滑窗上限
 *    (`readReportMaxPerMin`,默认 18 次);
 *  - 在独立守护线程 `pico-read-report` 串行执行,绝不阻塞内核回调线程或 `pico-msgpipe`;
 *  - 会话丢失/换号时通过 [reset] 清空会话态并递增 epoch,防止跨号串单。
 */
object ReadReporter {

    private const val MAX_CONVERSATIONS = 512
    private const val RATE_WINDOW_MS = 60_000L
    private const val CALLBACK_TIMEOUT_MS = 10_000L

    private data class ConvKey(val chatType: Int, val peerUid: String)

    private class ConvState(
        var pending: Int = 0,
        var firstPendingAt: Long = 0L,
        var lastReportAt: Long = 0L,
        var lastMsgAt: Long = 0L,
        var backoffUntil: Long = 0L
    )

    private val lock = Any()
    private val conversations = object : LinkedHashMap<ConvKey, ConvState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConvKey, ConvState>?): Boolean =
            size > MAX_CONVERSATIONS
    }
    private val sendTimestamps = ArrayDeque<Long>()
    private val wakeup = Semaphore(0)

    @Volatile
    private var running = false

    private var epoch: Long = 0L
    private var lastGlobalSendAt: Long = 0L

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
        val now = System.currentTimeMillis()
        var shouldWake = false
        synchronized(lock) {
            val key = ConvKey(chatType, peerUid)
            val st = conversations[key] ?: ConvState().also { conversations[key] = it }
            st.pending++
            if (st.firstPendingAt == 0L) st.firstPendingAt = now
            st.lastMsgAt = now
            if (st.pending >= Config.readReportBatch) shouldWake = true
        }
        if (shouldWake && wakeup.availablePermits() == 0) {
            wakeup.release()
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
        val snapEpoch: Long
        val snapPending: Int
        synchronized(lock) {
            snapEpoch = epoch
            snapPending = conversations[key]?.pending ?: 0
            recordGlobalAttemptLocked(System.currentTimeMillis())
        }

        val (ok, detail) = invokeSetMsgRead(msgService, chatType, peerUid)
        val now = System.currentTimeMillis()
        if (ok) {
            synchronized(lock) {
                if (epoch == snapEpoch) {
                    val st = conversations[key]
                    if (st != null) {
                        st.pending = (st.pending - snapPending).coerceAtLeast(0)
                        st.lastReportAt = now
                        st.firstPendingAt = if (st.pending > 0) now else 0L
                        st.backoffUntil = 0L
                    }
                }
            }
            Counters.onReadReportSent()
            PicoLog.i("manual read report ok chat=$chatType peer=$peerUid")
        } else {
            Counters.onReadReportFailed()
            PicoLog.w("manual read report failed chat=$chatType peer=$peerUid $detail")
        }
        return ok to detail
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

        val now = System.currentTimeMillis()
        val batch = Config.readReportBatch
        val intervalMs = Config.readReportIntervalMs
        val minGapMs = Config.readReportMinGapMs
        val maxPerMin = Config.readReportMaxPerMin

        var targetKey: ConvKey? = null
        var snapPending = 0
        var snapEpoch = 0L
        var rateWaitMs = 0L

        synchronized(lock) {
            pruneRateWindowLocked(now)

            // 优先选 pending 最多且达到批次或超时阈值的会话(避免热点群逼近 30 未读)
            var bestPending = 0
            var bestFirstAt = Long.MAX_VALUE
            for ((k, st) in conversations) {
                if (st.pending <= 0 || now < st.backoffUntil) continue
                val baseAt = if (st.lastReportAt > 0L) maxOf(st.lastReportAt, st.firstPendingAt) else st.firstPendingAt
                val due = st.pending >= batch || (baseAt > 0L && now - baseAt >= intervalMs)
                if (!due) continue
                if (st.pending > bestPending || (st.pending == bestPending && st.firstPendingAt < bestFirstAt)) {
                    targetKey = k
                    bestPending = st.pending
                    bestFirstAt = st.firstPendingAt
                }
            }
            if (targetKey == null) return 1000L

            // 全局最小间隔 + 每分钟滑窗上限
            val gapRemain = minGapMs - (now - lastGlobalSendAt)
            if (lastGlobalSendAt > 0L && gapRemain > 0L) {
                rateWaitMs = gapRemain
            } else if (sendTimestamps.size >= maxPerMin) {
                val oldest = sendTimestamps.peekFirst() ?: now
                rateWaitMs = ((oldest + RATE_WINDOW_MS) - now).coerceAtLeast(500L)
            } else {
                snapPending = bestPending
                snapEpoch = epoch
                recordGlobalAttemptLocked(now)
            }
        }

        if (rateWaitMs > 0L) return rateWaitMs
        val key = targetKey ?: return 1000L

        val (ok, detail) = invokeSetMsgRead(msgService, key.chatType, key.peerUid)
        val finishedAt = System.currentTimeMillis()
        if (ok) {
            synchronized(lock) {
                if (epoch == snapEpoch) {
                    val st = conversations[key]
                    if (st != null) {
                        st.pending = (st.pending - snapPending).coerceAtLeast(0)
                        st.lastReportAt = finishedAt
                        st.firstPendingAt = if (st.pending > 0) finishedAt else 0L
                        st.backoffUntil = 0L
                    }
                }
            }
            Counters.onReadReportSent()
            PicoLog.d("read report ok chat=${key.chatType} peer=${key.peerUid} cleared=$snapPending")
        } else {
            synchronized(lock) {
                if (epoch == snapEpoch) {
                    val st = conversations[key]
                    if (st != null) {
                        // 失败时强制退避一个周期(即使 pending>=batch),避免热点会话反复打 RPC
                        st.lastReportAt = finishedAt
                        st.firstPendingAt = finishedAt
                        st.backoffUntil = finishedAt + intervalMs
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

    private fun pruneRateWindowLocked(now: Long) {
        while (sendTimestamps.isNotEmpty()) {
            val head = sendTimestamps.peekFirst() ?: break
            if (now - head >= RATE_WINDOW_MS) sendTimestamps.removeFirst() else break
        }
    }

    private fun recordGlobalAttemptLocked(now: Long) {
        pruneRateWindowLocked(now)
        lastGlobalSendAt = now
        sendTimestamps.addLast(now)
    }

    fun stats(): JSONObject {
        val now = System.currentTimeMillis()
        var pendingConvs = 0
        var pendingMsgs = 0
        var tracked = 0
        var windowCount = 0
        var lastSend = 0L
        synchronized(lock) {
            pruneRateWindowLocked(now)
            tracked = conversations.size
            windowCount = sendTimestamps.size
            lastSend = lastGlobalSendAt
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
