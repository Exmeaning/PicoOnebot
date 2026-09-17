package pico.onebot.core

/**
 * 发送侧令牌桶:降低风控面,也防上游插件刷屏把会话打死。
 * 超限时**排队等待**而不是丢弃(调用方本来就在自己的动作线程上)。
 */
class RateLimiter(private val permitsPerSec: Int) {

    private var allowance: Double = permitsPerSec.toDouble()
    private var lastCheck: Long = System.currentTimeMillis()

    @Synchronized
    fun acquire(maxWaitMs: Long = 10_000): Boolean {
        if (permitsPerSec <= 0) return true
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (true) {
            val now = System.currentTimeMillis()
            allowance += (now - lastCheck) / 1000.0 * permitsPerSec
            lastCheck = now
            if (allowance > permitsPerSec) allowance = permitsPerSec.toDouble()
            if (allowance >= 1.0) {
                allowance -= 1.0
                return true
            }
            if (now >= deadline) return false
            val needMs = ((1.0 - allowance) / permitsPerSec * 1000).toLong().coerceAtLeast(10)
            try {
                Thread.sleep(minOf(needMs, deadline - now))
            } catch (t: InterruptedException) {
                return false
            }
        }
    }
}
