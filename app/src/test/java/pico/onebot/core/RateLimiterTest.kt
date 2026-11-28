package pico.onebot.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RateLimiterTest {

    @Test
    fun nonPositiveRateIsUnlimited() {
        val zeroLimiter = RateLimiter(0)
        val negativeLimiter = RateLimiter(-10)
        repeat(20) {
            assertTrue(zeroLimiter.acquire(maxWaitMs = 0L))
            assertTrue(negativeLimiter.acquire(maxWaitMs = 0L))
        }
    }

    @Test
    fun tokenBucketPacingAndTimeoutWithMonotonicClock() {
        // Bucket initializes with allowance = 2.0 permits at 2 permits/sec (500 ms per refill)
        val limiter = RateLimiter(2)

        assertTrue("1st initial burst permit should succeed immediately", limiter.acquire(maxWaitMs = 0L))
        assertTrue("2nd initial burst permit should succeed immediately", limiter.acquire(maxWaitMs = 0L))
        assertFalse("3rd permit with 0ms wait should fail when bucket is empty", limiter.acquire(maxWaitMs = 0L))

        val startNs = System.nanoTime()
        assertTrue("Acquire with sufficient budget should refill and succeed", limiter.acquire(maxWaitMs = 1_500L))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        assertTrue("Expected monotonic refill pacing (~500ms), got ${elapsedMs}ms", elapsedMs >= 300L)
    }

    @Test
    fun dynamicConfigUpdateClampsBucketAndAppliesImmediately() {
        val rate = AtomicInteger(10)
        val limiter = RateLimiter { rate.get() }

        // Lower rate dynamically from 10 -> 1: existing allowance (10) must clamp to new ceiling (1)
        rate.set(1)
        assertTrue("1st permit under lowered rate=1 should succeed", limiter.acquire(maxWaitMs = 0L))
        assertFalse("2nd immediate permit must be blocked after clamping allowance to 1", limiter.acquire(maxWaitMs = 0L))

        // Disable rate limiting dynamically (rate <= 0)
        rate.set(0)
        assertTrue("Dynamic rate=0 must immediately disable throttling", limiter.acquire(maxWaitMs = 0L))

        // Re-enable at higher rate (50 permits/sec -> 20ms per permit)
        rate.set(50)
        assertTrue("Higher dynamic rate should refill quickly", limiter.acquire(maxWaitMs = 500L))
    }

    @Test
    fun interruptDuringWaitReturnsFalseAndRestoresFlag() {
        val limiter = RateLimiter(1)
        assertTrue(limiter.acquire(maxWaitMs = 0L))

        val acquired = AtomicBoolean(true)
        val flagRestored = AtomicBoolean(false)

        val worker = Thread {
            Thread.currentThread().interrupt()
            acquired.set(limiter.acquire(maxWaitMs = 2_000L))
            flagRestored.set(Thread.currentThread().isInterrupted)
            Thread.interrupted()
        }
        worker.start()
        worker.join(2_000L)

        assertFalse(worker.isAlive)
        assertFalse("Interrupted wait must return false", acquired.get())
        assertTrue("RateLimiter must restore thread interrupt flag", flagRestored.get())
    }
}
