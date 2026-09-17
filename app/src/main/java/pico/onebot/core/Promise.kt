package pico.onebot.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 内核全是回调式,这里把"一次性回调"转成可超时等待的值。
 * minSdk 21,没有 CompletableFuture,所以用 ArrayBlockingQueue(1)。
 *
 * 约束:**内核回调线程不得调用 [await]**(会把内核的回调线程堵死)。
 */
class Promise<T> {

    private class Box(val value: Any?)

    private val slot = ArrayBlockingQueue<Box>(1)

    /** 多次 resolve 只有第一次生效。 */
    fun resolve(value: T?) {
        slot.offer(Box(value))
    }

    /** @return null 表示超时;拿到值(可能是 null 值)用 [awaitBox] 区分。 */
    fun await(timeoutMs: Long): T? = awaitBox(timeoutMs)?.value as T?

    /** @return null 表示超时。 */
    private fun awaitBox(timeoutMs: Long): Box? =
        slot.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** true = 在超时前完成。 */
    fun awaited(timeoutMs: Long): Boolean = awaitBox(timeoutMs) != null
}
