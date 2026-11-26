package pico.onebot.event

import org.json.JSONObject
import pico.onebot.core.Config
import pico.onebot.core.PicoLog
import pico.onebot.net.Transport
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 顺序闸:内核回调线程只做"拷贝 + 入队",由**单条 dispatch 线程**出队 → 序列化一次 → 多路广播。
 * 队列满时丢最老的并计数,**绝不阻塞内核回调线程**。
 */
object EventBus {

    private val offerLock = Any()

    private lateinit var queue: ArrayBlockingQueue<JSONObject>

    private val posted = AtomicLong()
    private val dropped = AtomicLong()
    private val delivered = AtomicLong()
    private val noConsumer = AtomicLong()

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        queue = ArrayBlockingQueue(Config.eventQueueSize)
        running = true
        Thread({ pump() }, "pico-event").apply { isDaemon = true }.start()
        PicoLog.i("event bus started, queue=" + Config.eventQueueSize)
    }

    fun post(event: JSONObject) {
        if (!running) return
        posted.incrementAndGet()
        synchronized(offerLock) {
            while (!queue.offer(event)) {
                if (queue.poll() != null) dropped.incrementAndGet()
            }
        }
    }

    private fun pump() {
        while (running) {
            val e = try {
                queue.take()
            } catch (t: InterruptedException) {
                if (!running) return
                PicoLog.w("event bus interrupted while running, continuing")
                continue
            } catch (t: Throwable) {
                PicoLog.w("event bus take failed", t)
                continue
            }
            try {
                val text = e.toString()
                val isMeta = e.optString("post_type") == "meta_event"
                val wsDelivered = Transport.broadcast(text, isMeta)
                val hasHttpConsumer = pico.onebot.net.HttpReporter.targetCount() > 0
                pico.onebot.net.HttpReporter.offer(text)
                val hasConsumers = wsDelivered || hasHttpConsumer
                if (hasConsumers) {
                    delivered.incrementAndGet()
                    if (!isMeta) PicoLog.d("event -> " + brief(e))
                } else {
                    noConsumer.incrementAndGet()
                    if (!isMeta) {
                        PicoLog.w("event has no active WS/HTTP consumer: " + brief(e))
                    }
                }
            } catch (t: Throwable) {
                PicoLog.w("event dispatch failed", t)
            }
        }
    }

    private fun brief(e: JSONObject): String {
        val pt = e.optString("post_type")
        return when (pt) {
            "message", "message_sent" -> pt + " " + e.optString("message_type") +
                " id=" + e.optInt("message_id") + " from=" + e.optLong("user_id")
            "notice" -> "notice " + e.optString("notice_type")
            "request" -> "request " + e.optString("request_type")
            else -> pt
        }
    }

    fun stats(): JSONObject = JSONObject()
        .put("posted", posted.get())
        .put("delivered", delivered.get())
        .put("no_consumer", noConsumer.get())
        .put("dropped", dropped.get())
        .put("pending", if (running) queue.size else 0)
        .put("capacity", if (running) Config.eventQueueSize else 0)
}
