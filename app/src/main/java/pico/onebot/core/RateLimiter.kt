package pico.onebot.core

import java.util.concurrent.TimeUnit

/**
 * 发送侧令牌桶:降低风控面,也防上游插件刷屏把会话打死。
 * 超限时**排队等待**而不是丢弃(调用方本来就在自己的动作线程上)。
 *
 * 速率可热改:值由 [permitsPerSec] 这个取值器在每次 acquire 时现取,
 * 而不是构造时冻结。改完 WebUI 设置立刻生效。
 */
class RateLimiter(private val permitsPerSec: () -> Int) {

    constructor(permitsPerSec: Int) : this({ permitsPerSec })

    private val lock = Any()
    private var allowance: Double = permitsPerSec().coerceAtLeast(0).toDouble()
    private var lastCheckNs: Long = System.nanoTime()
    /** 上一次生效的速率,用于在速率变化时把桶容量同步过去。 */
    private var lastRate: Int = permitsPerSec()

    fun acquire(maxWaitMs: Long = 10_000): Boolean {
        if (permitsPerSec() <= 0) return true
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs.coerceAtLeast(0L))
        while (true) {
            val sleepMs: Long
            synchronized(lock) {
                val rate = permitsPerSec()
                if (rate <= 0) return true
                if (rate != lastRate) {
                    // 速率变了:令牌上限跟着变,已积累的余量不能超过新上限
                    if (allowance > rate) allowance = rate.toDouble()
                    if (allowance < 0.0) allowance = 0.0
                    lastRate = rate
                }
                val nowNs = System.nanoTime()
                val elapsedNs = (nowNs - lastCheckNs).coerceAtLeast(0L)
                lastCheckNs = nowNs
                allowance += (elapsedNs / 1_000_000_000.0) * rate
                if (allowance > rate) allowance = rate.toDouble()
                if (allowance >= 1.0) {
                    allowance -= 1.0
                    return true
                }
                val remainNs = deadlineNs - nowNs
                if (remainNs <= 0L) return false
                val needMs = ((1.0 - allowance) / rate * 1000.0).toLong().coerceAtLeast(10L)
                val remainMs = (remainNs / 1_000_000L).coerceAtLeast(1L)
                sleepMs = minOf(needMs, remainMs)
            }
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }
}
