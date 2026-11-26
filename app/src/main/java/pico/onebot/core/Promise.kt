package pico.onebot.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内核全是回调式,这里把"一次性回调"转成可超时等待的值。
 * minSdk 21,没有 CompletableFuture,使用 CountDownLatch(1) + AtomicBoolean + @Volatile。
 *
 * - 严格 write-once:多次 [resolve] 只有第一次生效;
 * - 支持多并发等待者与重复 [await],不会因首个读取者消费而令其他等待者死等;
 * - 约束:**内核回调线程不得调用 [await]**(会把内核的回调线程堵死)。
 */
class Promise<T> {

    private val latch = CountDownLatch(1)
    private val resolved = AtomicBoolean(false)

    @Volatile
    private var result: T? = null

    /** 多次 resolve 只有第一次生效。 */
    fun resolve(value: T?) {
        if (resolved.compareAndSet(false, true)) {
            result = value
            latch.countDown()
        }
    }

    /** @return null 表示超时(或已 resolve 为 null);用 [awaited] 可区分是否完成。 */
    fun await(timeoutMs: Long): T? = if (awaitLatch(timeoutMs)) result else null

    /** true = 在超时前完成。 */
    fun awaited(timeoutMs: Long): Boolean = awaitLatch(timeoutMs)

    private fun awaitLatch(timeoutMs: Long): Boolean {
        if (latch.count == 0L) return true
        return try {
            latch.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
