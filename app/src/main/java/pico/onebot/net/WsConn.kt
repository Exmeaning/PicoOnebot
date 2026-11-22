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

        /**
         * "尽力发 CLOSE 帧"允许占用的最长时间。
         *
         * 这个上限是死锁防线,不是性能调优:`sendText()` 队列溢出时是在
         * [Transport.broadcast] 的锁内调用 [close] 的,而慢连接的写线程正
         * 持有写锁卡在 socket 写上。close() 若在这里无限等待,整个事件广播
         * (含其它健康连接)都会跟着停摆。
         */
        const val CLOSE_FRAME_TIMEOUT_MS = 100L
    }

    /**
     * 串行化对 `out` 的写。用 [ReentrantLock] 而不是 `synchronized`,
     * 是为了让 [close] 能 [ReentrantLock.tryLock] —— 见 close() 的注释。
     */
    private val writeLock = ReentrantLock()
    private val queueLock = ReentrantLock()
    private val notEmpty = queueLock.newCondition()
    private val pending = ArrayDeque<String>(PENDING_CAPACITY)
    private val droppedEvents = AtomicLong()
    private val closeRequested = java.util.concurrent.atomic.AtomicBoolean(false)
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
        // close() 自己会拿 queueLock,必须放到锁外;而且这里常常是在
        // Transport.broadcast() 持锁期间被调到的,所以关连接的动作要挪到
        // 后台线程,不能让广播线程陪着等写锁(见 close() 里的注释)。
        if (overflowed) {
            droppedEvents.incrementAndGet()
            PicoLog.w("ws send queue overflow on $tag; closing slow peer (totalOverflow=" + droppedEvents.get() + ")")
            closeAsync()
        }
        return false
    }

    /** 把一个可能阻塞的 [close] 挪到后台线程;重复调用只生效一次。 */
    private fun closeAsync() {
        if (!closeRequested.compareAndSet(false, true)) return
        Thread({ close() }, "pico-ws-close").apply { isDaemon = true }.start()
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
            writeLock.lock()
            try {
                WsFrame.write(out, opcode, payload, maskOut)
            } finally {
                writeLock.unlock()
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
        // 尽力发一个 CLOSE 帧(状态码 1000 正常关闭),让对端按正常关闭处理
        // 而不是只看到 TCP 断开、记成 1006 异常关闭。失败无所谓,反正要关了。
        //
        // 必须 tryLock 且限时:慢连接(半开 TCP / 对端不读)的典型形态就是写线程
        // 正持有 writeLock 阻塞在 socket 写上,而 close() 恰是在 Transport.broadcast()
        // 持锁期间由 sendText() 溢出路径调进来的。这里若无条件等锁,socket.close()
        // 永远轮不到执行,广播锁就跟着永远拿不回来 —— 全部连接一起卡死。
        // 拿不到锁就跳过 CLOSE 帧,直接关 socket 让写线程自己失败退出。
        try {
            if (writeLock.tryLock(CLOSE_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                try {
                    WsFrame.write(out, WsFrame.OP_CLOSE, closePayload(1000), maskOut)
                } finally {
                    writeLock.unlock()
                }
            }
        } catch (ignored: Throwable) {
        }
        // 先关 socket:这一步会唤醒卡在底层写/读上的线程,且本身不拿任何锁
        try {
            socket.close()
        } catch (ignored: Throwable) {
        }
        writer.interrupt()
    }

    /** RFC 6455 §5.5.1:关闭帧负载是 2 字节大端状态码。 */
    private fun closePayload(code: Int): ByteArray =
        byteArrayOf(((code shr 8) and 0xFF).toByte(), (code and 0xFF).toByte())

    /**
     * 阻塞读循环:处理分片、ping/pong、close;每条完整文本回调一次。
     * 返回即代表连接已结束。
     */
    fun loop(onText: (String) -> Unit) {
        val acc = ByteArrayOutputStream()
        var accOpcode = -1
        // maskOut=true 说明本端是反向 WS 的客户端,读的是服务端帧(不带掩码);
        // 否则本端是服务端,读客户端帧时必须带掩码。
        val requireMask = !maskOut
        try {
            while (!closed) {
                val f = WsFrame.read(ins, requireMask)
                when (f.opcode) {
                    WsFrame.OP_PING -> if (!writeFrame(WsFrame.OP_PONG, f.payload)) return
                    WsFrame.OP_PONG -> Unit
                    WsFrame.OP_CLOSE -> return
                    WsFrame.OP_TEXT, WsFrame.OP_BIN -> {
                        if (f.fin) {
                            // 分片未结束时收到新的 TEXT/BIN 是协议错误,不能把已累积的内容
                            // 丢掉继续跑(那会静默产出拼接错乱的文本)。
                            if (accOpcode != -1) throw java.io.IOException("protocol error: new message while fragment open")
                            if (f.opcode == WsFrame.OP_TEXT) onText(String(f.payload, Charsets.UTF_8))
                        } else {
                            if (accOpcode != -1) throw java.io.IOException("protocol error: new fragment start while fragment open")
                            acc.reset()
                            acc.write(f.payload)
                            accOpcode = f.opcode
                        }
                    }
                    WsFrame.OP_CONT -> {
                        // 没有起始分片就来 CONT 也是协议错误
                        if (accOpcode == -1) throw java.io.IOException("protocol error: continuation without start")
                        acc.write(f.payload)
                        if (acc.size() > WsFrame.MAX_MESSAGE) {
                            throw java.io.IOException("protocol error: fragmented message exceeds " + WsFrame.MAX_MESSAGE)
                        }
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
