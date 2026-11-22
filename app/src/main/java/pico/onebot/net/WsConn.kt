package pico.onebot.net

import pico.onebot.core.PicoLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * 一条 WebSocket 连接(正向服务端接进来的,或反向客户端连出去的都用它)。
 *
 * 角色:OneBot 11 的 `/api` 只收动作、`/event` 只推事件、`/` 两者都要。
 */
class WsConn(
    private val socket: Socket,
    private val ins: InputStream,
    private val out: OutputStream,
    val tag: String,
    val role: Role,
    /** 客户端角色必须给帧加掩码(RFC 6455)。 */
    private val maskOut: Boolean,
    /** 产生这条连接的网络配置项 id;WebUI 要按配置项如实统计在线对端。 */
    val ownerId: String = ""
) {

    enum class Role { API, EVENT, BOTH }

    private companion object {
        const val PENDING_CAPACITY = 1024
        const val PING_INTERVAL_MS = 20_000L
        const val READ_IDLE_TIMEOUT_MS = 60_000
    }

    private val writeLock = Object()
    private val queueLock = ReentrantLock()
    private val notEmpty = queueLock.newCondition()
    private val pending = ArrayDeque<String>(PENDING_CAPACITY)
    private val droppedEvents = AtomicLong()
    private val writer = Thread({ writeLoop() }, "pico-ws-send").apply { isDaemon = true }

    @Volatile
    var closed = false
        private set

    init {
        try {
            socket.keepAlive = true
            socket.soTimeout = READ_IDLE_TIMEOUT_MS
        } catch (_: Throwable) {
        }
        writer.start()
    }

    val wantsEvents: Boolean get() = role == Role.EVENT || role == Role.BOTH
    val wantsActions: Boolean get() = role == Role.API || role == Role.BOTH

    /** 因发送队列溢出而被迫关闭的次数(每条连接累计)。 */
    fun overflowCount(): Long = droppedEvents.get()

    /**
     * 入队一条待发文本。
     *
     * 队列满时**不再"丢最老却返回 true"** —— 那会让 [Transport.broadcast] 误以为投递成功,
     * 既不写重连回放缓冲,也不告诉任何人某条事件已经没了。这里按 docs/config/network.md
     * 承诺的语义处理:关掉这条慢连接,由对端重连后从回放缓冲补齐。
     *
     * @return true 已入队;false 连接已关闭或已因队列溢出被关闭(本轮未投递)。
     */
    fun sendText(text: String): Boolean {
        var overflowed = false
        queueLock.lock()
        try {
            if (closed) return false
            if (pending.size >= PENDING_CAPACITY) {
                overflowed = true
            } else {
                pending.addLast(text)
                notEmpty.signal()
                return true
            }
        } finally {
            queueLock.unlock()
        }
        // close() 自己会拿 queueLock,必须放到锁外
        if (overflowed) {
            droppedEvents.incrementAndGet()
            PicoLog.w("ws send queue overflow on $tag; closing slow peer (totalOverflow=" + droppedEvents.get() + ")")
            close()
        }
        return false
    }

    private fun writeLoop() {
        var nextPingAt = System.currentTimeMillis() + PING_INTERVAL_MS
        try {
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= nextPingAt) {
                    if (!writeFrame(WsFrame.OP_PING, ByteArray(0))) return
                    nextPingAt = System.currentTimeMillis() + PING_INTERVAL_MS
                }
                var text: String? = null
                queueLock.lock()
                try {
                    while (!closed && pending.isEmpty()) {
                        val waitMs = nextPingAt - System.currentTimeMillis()
                        if (waitMs <= 0L) break
                        notEmpty.await(waitMs, TimeUnit.MILLISECONDS)
                    }
                    if (closed) return
                    text = pending.pollFirst()
                } finally {
                    queueLock.unlock()
                }
                if (text != null) {
                    if (!writeFrame(WsFrame.OP_TEXT, text.toByteArray(Charsets.UTF_8))) return
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray): Boolean {
        return try {
            synchronized(writeLock) {
                WsFrame.write(out, opcode, payload, maskOut)
            }
            true
        } catch (t: Throwable) {
            if (!closed) PicoLog.w("ws send failed on $tag: " + t.message)
            close()
            false
        }
    }

    fun close() {
        queueLock.lock()
        try {
            if (closed) return
            closed = true
            pending.clear()
            notEmpty.signalAll()
        } finally {
            queueLock.unlock()
        }
        try {
            socket.close()
        } catch (ignored: Throwable) {
        }
        writer.interrupt()
    }

    /**
     * 阻塞读循环:处理分片、ping/pong、close;每条完整文本回调一次。
     * 返回即代表连接已结束。
     */
    fun loop(onText: (String) -> Unit) {
        val acc = ByteArrayOutputStream()
        var accOpcode = -1
        try {
            while (!closed) {
                val f = WsFrame.read(ins)
                when (f.opcode) {
                    WsFrame.OP_PING -> if (!writeFrame(WsFrame.OP_PONG, f.payload)) return
                    WsFrame.OP_PONG -> Unit
                    WsFrame.OP_CLOSE -> return
                    WsFrame.OP_TEXT, WsFrame.OP_BIN -> {
                        if (f.fin) {
                            if (f.opcode == WsFrame.OP_TEXT) onText(String(f.payload, Charsets.UTF_8))
                        } else {
                            acc.reset()
                            acc.write(f.payload)
                            accOpcode = f.opcode
                        }
                    }
                    WsFrame.OP_CONT -> {
                        acc.write(f.payload)
                        if (f.fin) {
                            if (accOpcode == WsFrame.OP_TEXT) {
                                onText(String(acc.toByteArray(), Charsets.UTF_8))
                            }
                            acc.reset()
                            accOpcode = -1
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            PicoLog.d("ws loop end on $tag: " + t.javaClass.simpleName + " " + t.message)
        } finally {
            close()
        }
    }
}
