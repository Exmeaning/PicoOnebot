package pico.onebot.core

import java.util.concurrent.atomic.AtomicLong

/**
 * 全局收发计数(BotStatus 的 `msgSent` / `msgRecv` 来源)。
 * 只统计"成真的 OneBot 消息":收到并转成事件的入站消息、发送成功的出站消息。
 * 进程内计数,重启归零 —— 面板上是"本次运行以来",不做持久化。
 */
object Counters {

    private val sent = AtomicLong()
    private val recv = AtomicLong()
    private val lastRecvAtMs = AtomicLong()
    private val readReportsSent = AtomicLong()
    private val readReportsFailed = AtomicLong()
    private val syncTriggered = AtomicLong()
    private val lastSyncAtMs = AtomicLong()

    fun onSent() { sent.incrementAndGet() }
    fun onRecv() { recv.incrementAndGet() }

    /** 仅由非自发入站消息更新,用于静默观测。 */
    fun onInboundRecv(at: Long = System.currentTimeMillis()) {
        while (true) {
            val prev = lastRecvAtMs.get()
            if (at <= prev) return
            if (lastRecvAtMs.compareAndSet(prev, at)) return
        }
    }

    /** 换号/会话丢失时复位入站静默起点。 */
    fun resetLastRecvAt() { lastRecvAtMs.set(0L) }

    fun onReadReportSent() { readReportsSent.incrementAndGet() }
    fun onReadReportFailed() { readReportsFailed.incrementAndGet() }

    fun onSyncTriggered(at: Long = System.currentTimeMillis()) {
        syncTriggered.incrementAndGet()
        lastSyncAtMs.set(at)
    }

    fun sent(): Long = sent.get()
    fun recv(): Long = recv.get()
    fun lastRecvAt(): Long = lastRecvAtMs.get()
    fun readReportsSent(): Long = readReportsSent.get()
    fun readReportsFailed(): Long = readReportsFailed.get()
    fun syncTriggered(): Long = syncTriggered.get()
    fun lastSyncAt(): Long = lastSyncAtMs.get()
}
