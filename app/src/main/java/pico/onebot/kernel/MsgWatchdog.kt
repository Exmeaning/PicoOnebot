package pico.onebot.kernel

import org.json.JSONArray
import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.Counters
import pico.onebot.core.PicoLog
import java.util.concurrent.TimeUnit

/**
 * 入站消息静默可观测性与保守 `startMsgSync()` 兜底看门狗(P0)。
 *
 * 职责:
 *  1) 记录全局与按会话的 `lastRecvAt` / `lastMsgSeq` / `recvCount`,补足诊断盲区;
 *  2) 仅在 `ONLINE` 且本会话已收过至少 1 条入站消息后,连续静默达到阈值(默认 600s)时,
 *     以退避策略(`1x -> 2x -> 3x`,默认 `10m -> 20m -> 30m`,最多 3 次)触发
 *     `IKernelMsgService.startMsgSync()`(`SsoInfoSync` + `SsoSyncGroupMsg` 兜底对齐);
 *     收到任意新入站消息立即复位退避计数;
 *  3) 暴露按需运维入口 [triggerSyncNow] 及只读最近会话未读摘要 [recentContactSummary]。
 *
 * 所有时长/冷却/退避均采用单调时钟 `System.nanoTime()`,免疫墙钟回跳;墙钟仅用于外部诊断时间戳。
 */
object MsgWatchdog {

    private const val MAX_TRACKED_CONVS = 512
    private const val WATCH_TICK_MS = 15_000L
    private const val MANUAL_SYNC_COOLDOWN_NS = 5_000_000_000L
    private const val RECENT_SCAN_LIMIT = 50
    private const val RECENT_TOP_UNREAD_LIMIT = 10

    private data class ConvKey(val chatType: Int, val peerUid: String)

    private class ConvActivity(
        var lastRecvAtWall: Long,
        var lastMsgSeq: Long,
        var recvCount: Long
    )

    private val lock = Any()
    private val conversations = object : LinkedHashMap<ConvKey, ConvActivity>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ConvKey, ConvActivity>?): Boolean =
            size > MAX_TRACKED_CONVS
    }

    @Volatile
    private var running = false

    private var lastRecvAtWall: Long = 0L
    private var lastRecvAtNs: Long = 0L
    private var hasReceivedAnyInSession: Boolean = false
    private var silenceSyncAttempts: Int = 0
    private var hasNextSyncAllowed: Boolean = false
    private var nextSyncAllowedAtNs: Long = 0L
    private var hasManualSync: Boolean = false
    private var lastManualSyncAtNs: Long = 0L

    fun start() {
        synchronized(this) {
            if (running) return
            running = true
        }
        Thread({ loop() }, "pico-msg-watchdog").apply { isDaemon = true }.start()
        PicoLog.i("msg watchdog started")
    }

    /** 收到非自发入站消息时打点,并立即复位本轮静默退避。 */
    fun onInboundMessage(chatType: Int, peerUid: String, msgSeq: Long) {
        val nowWall = System.currentTimeMillis()
        val nowNs = System.nanoTime()
        synchronized(lock) {
            lastRecvAtWall = nowWall
            lastRecvAtNs = nowNs
            hasReceivedAnyInSession = true
            silenceSyncAttempts = 0
            hasNextSyncAllowed = false
            nextSyncAllowedAtNs = 0L
            Counters.onInboundRecv(nowWall)
            if (peerUid.isNotEmpty() && (chatType == 1 || chatType == 2)) {
                val key = ConvKey(chatType, peerUid)
                val cur = conversations[key]
                if (cur == null) {
                    conversations[key] = ConvActivity(nowWall, msgSeq, 1L)
                } else {
                    cur.lastRecvAtWall = nowWall
                    if (msgSeq > cur.lastMsgSeq) cur.lastMsgSeq = msgSeq
                    cur.recvCount++
                }
            }
        }
    }

    /** 会话丢失/换号时清空会话级静默与序列状态。 */
    fun reset() {
        synchronized(lock) {
            conversations.clear()
            lastRecvAtWall = 0L
            lastRecvAtNs = 0L
            hasReceivedAnyInSession = false
            silenceSyncAttempts = 0
            hasNextSyncAllowed = false
            nextSyncAllowedAtNs = 0L
            Counters.resetLastRecvAt()
        }
        PicoLog.d("msg watchdog reset")
    }

    /** 当前连续静默时长(ms);尚未收过入站消息时返回 0。 */
    fun silenceMs(): Long {
        val hasRecv: Boolean
        val lastNs: Long
        synchronized(lock) {
            hasRecv = hasReceivedAnyInSession
            lastNs = lastRecvAtNs
        }
        if (!hasRecv) return 0L
        return (System.nanoTime() - lastNs).coerceAtLeast(0L) / 1_000_000L
    }

    /** 手工触发一次 `IKernelMsgService.startMsgSync()`(带 5s 单调时钟防抖冷却)。 */
    fun triggerSyncNow(reason: String): Pair<Boolean, String> {
        if (!KernelGate.ready) return false to "kernel not ready (${KernelGate.state})"
        val msgService = KernelGate.msgService() ?: return false to "msgService unavailable"
        val nowNs = System.nanoTime()
        val nowWall = System.currentTimeMillis()
        synchronized(lock) {
            if (hasManualSync && (nowNs - lastManualSyncAtNs).coerceAtLeast(0L) < MANUAL_SYNC_COOLDOWN_NS) {
                return false to "sync cooldown active, please retry in a few seconds"
            }
            hasManualSync = true
            lastManualSyncAtNs = nowNs
        }
        return try {
            msgService.startMsgSync()
            Counters.onSyncTriggered(nowWall)
            PicoLog.i("startMsgSync() triggered ($reason)")
            true to "ok"
        } catch (t: Throwable) {
            PicoLog.w("startMsgSync() failed ($reason)", t)
            false to (t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(WATCH_TICK_MS)
            } catch (_: InterruptedException) {
                if (!running) return
                continue
            }
            if (!running) return
            try {
                checkSilence()
            } catch (t: Throwable) {
                PicoLog.w("msg watchdog check failed", t)
            }
        }
    }

    private fun checkSilence() {
        val thresholdMs = Config.silenceSyncThresholdMs
        val maxAttempts = Config.silenceSyncMaxAttempts
        if (thresholdMs <= 0L || maxAttempts <= 0) return
        if (KernelGate.state != KernelGate.State.ONLINE) return
        val msgService = KernelGate.msgService() ?: return

        val nowNs = System.nanoTime()
        val nowWall = System.currentTimeMillis()
        val attemptNum: Int
        val silentMs: Long
        synchronized(lock) {
            if (!hasReceivedAnyInSession) return
            if (silenceSyncAttempts >= maxAttempts) return
            silentMs = (nowNs - lastRecvAtNs).coerceAtLeast(0L) / 1_000_000L
            if (silenceSyncAttempts == 0) {
                if (silentMs < thresholdMs) return
            } else {
                if (hasNextSyncAllowed && (nextSyncAllowedAtNs - nowNs) > 0L) return
            }
            silenceSyncAttempts++
            attemptNum = silenceSyncAttempts
            // 退避:第 1 次在 1x 阈值(10m)触发后,第 2 次等 2x(20m),第 3 次等 3x(30m)
            hasNextSyncAllowed = true
            nextSyncAllowedAtNs = nowNs + TimeUnit.MILLISECONDS.toNanos(thresholdMs) * (attemptNum + 1L)
        }

        try {
            msgService.startMsgSync()
            Counters.onSyncTriggered(nowWall)
            PicoLog.w(
                "msg silence watchdog: silent=${silentMs / 1000}s, triggered startMsgSync() " +
                    "attempt=$attemptNum/$maxAttempts"
            )
        } catch (t: Throwable) {
            PicoLog.w("msg silence watchdog: startMsgSync() failed (attempt=$attemptNum/$maxAttempts)", t)
        }
    }

    /** 只读查询近期会话未读概况(用于 `_pico_diagnostics` 排障,不发网络包)。 */
    fun recentContactSummary(): JSONObject {
        val out = JSONObject()
        val svc = KernelGate.recentContactService()
            ?: return out.put("available", false)
        return try {
            val info = svc.getRecentContactListSyncLimit(RECENT_SCAN_LIMIT)
            val list = info?.changedList ?: ArrayList()
            var unreadConvs = 0
            var maxUnread = 0L
            val unreadItems = ArrayList<com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo>()
            for (rc in list) {
                if (rc == null) continue
                if (rc.unreadCnt > 0L) {
                    unreadConvs++
                    if (rc.unreadCnt > maxUnread) maxUnread = rc.unreadCnt
                    unreadItems.add(rc)
                }
            }
            unreadItems.sortByDescending { it.unreadCnt }
            val topArr = JSONArray()
            for (rc in unreadItems.take(RECENT_TOP_UNREAD_LIMIT)) {
                topArr.put(
                    JSONObject()
                        .put("chat_type", rc.chatType)
                        .put("peer_uid", rc.peerUid ?: "")
                        .put("peer_uin", rc.peerUin)
                        .put("unread_cnt", rc.unreadCnt)
                        .put("shield_flag", rc.shieldFlag)
                        .put("msg_seq", rc.msgSeq)
                        .put("msg_time", rc.msgTime)
                )
            }
            out.put("available", true)
                .put("err_code", info?.errCode ?: 0)
                .put("scanned", list.size)
                .put("unread_conversations", unreadConvs)
                .put("max_unread_cnt", maxUnread)
                .put("top_unread", topArr)
        } catch (t: Throwable) {
            out.put("available", false)
                .put("error", t.javaClass.simpleName + ": " + t.message)
        }
    }

    fun stats(): JSONObject {
        val nowWall = System.currentTimeMillis()
        val nowNs = System.nanoTime()
        val lastWall: Long
        val hasRecv: Boolean
        val lastNs: Long
        val attempts: Int
        val nextAtWall: Long
        val tracked: Int
        val topConvs = JSONArray()
        synchronized(lock) {
            lastWall = lastRecvAtWall
            hasRecv = hasReceivedAnyInSession
            lastNs = lastRecvAtNs
            attempts = silenceSyncAttempts
            nextAtWall = if (hasNextSyncAllowed) {
                val remainMs = (nextSyncAllowedAtNs - nowNs).coerceAtLeast(0L) / 1_000_000L
                nowWall + remainMs
            } else 0L
            tracked = conversations.size
            val sorted = conversations.entries.sortedByDescending { it.value.lastRecvAtWall }.take(20)
            for ((k, v) in sorted) {
                topConvs.put(
                    JSONObject()
                        .put("chat_type", k.chatType)
                        .put("peer_uid", k.peerUid)
                        .put("last_recv_at", v.lastRecvAtWall)
                        .put("last_msg_seq", v.lastMsgSeq)
                        .put("recv_count", v.recvCount)
                )
            }
        }
        val silent = if (hasRecv) (nowNs - lastNs).coerceAtLeast(0L) / 1_000_000L else 0L
        return JSONObject()
            .put("last_recv_at", lastWall)
            .put("silence_ms", silent)
            .put("sync_triggered", Counters.syncTriggered())
            .put("last_sync_at", Counters.lastSyncAt())
            .put("silence_sync_attempts", attempts)
            .put("silence_sync_next_at", nextAtWall)
            .put("silence_sync_threshold_sec", Config.silenceSyncThresholdMs / 1000L)
            .put("silence_sync_max_attempts", Config.silenceSyncMaxAttempts)
            .put("tracked_conversations", tracked)
            .put("recent_active", topConvs)
    }
}
