package pico.onebot.core

/**
 * 发送侧令牌桶:降低风控面,也防上游插件刷屏把会话打死。
 * 超限时**排队等待**而不是丢弃(调用方本来就在自己的动作线程上)。
 *
 * 速率每小时可改:值由 [permitsPerSec] 这个取值器在每次 acquire 时现取,
 * 而不是构造时冻结。改完 WebUI 设置立刻生效。
 */
class RateLimiter(private val permitsPerSec: () -> Int) {

    constructor(permitsPerSec: Int) : this({ permitsPerSec })

    private var allowance: Double = permitsPerSec().toDouble()
    private var lastCheck: Long = System.currentTimeMillis()
    /** 上一次生效的速率,用于在速率变化时把桶容量同步过去。 */
    private var lastRate: Int = permitsPerSec()

    @Synchronized
    fun acquire(maxWaitMs: Long = 10_000): Boolean {
        val rate = permitsPerSec()
        if (rate <= 0) return true
        if (rate != lastRate) {
            // 速率变了:令牌上限跟着变,已积累的余量不能超过新上限
            if (allowance > rate) allowance = rate.toDouble()
            lastRate = rate
        }
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (true) {
            val now = System.currentTimeMillis()
            allowance += (now - lastCheck) / 1000.0 * rate
            lastCheck = now
            if (allowance > rate) allowance = rate.toDouble()
            if (allowance >= 1.0) {
                allowance -= 1.0
                return true
            }
            if (now >= deadline) return false
            val needMs = ((1.0 - allowance) / rate * 1000).toLong().coerceAtLeast(10)
            try {
                Thread.sleep(minOf(needMs, deadline - now))
            } catch (t: InterruptedException) {
                return false
            }
        }
    }
}
