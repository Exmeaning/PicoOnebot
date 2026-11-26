package pico.onebot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PromiseTest {

    @Test
    fun multiWaiterWakeupAndRepeatedAwait() {
        val promise = Promise<String>()
        val waiterCount = 6
        val started = CountDownLatch(waiterCount)
        val finished = CountDownLatch(waiterCount)
        val values = CopyOnWriteArrayList<String?>()

        val threads = (0 until waiterCount).map {
            Thread {
                started.countDown()
                values.add(promise.await(2_000L))
                finished.countDown()
            }.apply { start() }
        }

        assertTrue("Waiters should start promptly", started.await(2L, TimeUnit.SECONDS))
        promise.resolve("hello-pico")

        assertTrue("All waiters must wake up after resolve", finished.await(2L, TimeUnit.SECONDS))
        threads.forEach { it.join(1_000L) }

        assertEquals(waiterCount, values.size)
        assertTrue(values.all { it == "hello-pico" })

        // Repeated await / awaited after resolution must return immediately with same value
        assertTrue(promise.awaited(0L))
        assertEquals("hello-pico", promise.await(0L))
    }

    @Test
    fun writeOnceSemanticsPreservesFirstValueIncludingNull() {
        val nonNullPromise = Promise<String>()
        nonNullPromise.resolve("first")
        nonNullPromise.resolve("second")
        nonNullPromise.resolve(null)

        assertTrue(nonNullPromise.awaited(100L))
        assertEquals("first", nonNullPromise.await(100L))

        val nullPromise = Promise<String>()
        nullPromise.resolve(null)
        nullPromise.resolve("ignored-later")

        assertTrue("awaited() must distinguish resolved-null from timeout", nullPromise.awaited(100L))
        assertNull(nullPromise.await(100L))
    }

    @Test
    fun timeoutReturnsNullAndAwaitedReturnsFalse() {
        val promise = Promise<Int>()
        assertNull(promise.await(30L))
        assertFalse(promise.awaited(20L))
        // Negative timeouts must be safely clamped to 0 without throwing
        assertNull(promise.await(-50L))
        assertFalse(promise.awaited(-50L))
    }

    @Test
    fun interruptRestoresThreadInterruptFlag() {
        val promise = Promise<String>()
        val resultRef = AtomicReference<String?>("sentinel")
        val awaitedRef = AtomicBoolean(true)
        val flagPreserved = AtomicBoolean(false)
        val enteringWait = CountDownLatch(1)

        val worker = Thread {
            enteringWait.countDown()
            resultRef.set(promise.await(5_000L))
            flagPreserved.set(Thread.currentThread().isInterrupted)
            // Calling awaited while already interrupted also returns false and keeps flag set
            awaitedRef.set(promise.awaited(50L))
            flagPreserved.set(flagPreserved.get() && Thread.currentThread().isInterrupted)
            // Clear interrupt status before worker exits
            Thread.interrupted()
        }
        worker.start()

        assertTrue(enteringWait.await(2L, TimeUnit.SECONDS))
        Thread.sleep(30L)
        worker.interrupt()
        worker.join(2_000L)

        assertFalse("Worker thread must finish promptly after interrupt", worker.isAlive)
        assertNull(resultRef.get())
        assertFalse(awaitedRef.get())
        assertTrue("Promise must restore Thread.currentThread().interrupt()", flagPreserved.get())
    }
}
