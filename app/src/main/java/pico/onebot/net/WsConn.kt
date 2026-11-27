package pico.onebot.net

import pico.onebot.core.PicoLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    val ownerId: String = "",
    /** 可选的周期性保活检查(每轮 PING 前触发;返回 false 时以 1008 关闭连接,如校验日志流会话 TTL)。 */
    private val onPingCheck: (() -> Boolean)? = null
) {

    enum class Role { API, EVENT, BOTH }

    private companion object {
        const val PENDING_CAPACITY = 1024
        const val PING_INTERVAL_NS = 20_000_000_000L
        const val READ_IDLE_TIMEOUT_MS = 60_000
        const val ACC_SHRINK_THRESHOLD = 64 * 1024

        /**
         * "尽力发 CLOSE 帧"允许占用的最长时间。
         *
         * 这个上限是死锁防线,不是性能调优:`sendText()` 队列溢出时是在
         * [Transport.broadcast] 的锁内触发关闭的,而慢连接的写线程正
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
    private val closeRequested = AtomicBoolean(false)
    private val teardownStarted = AtomicBoolean(false)
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
     * 承诺的语义处理:在锁内立即原子标记关闭并触发后台关连接,然后才记录日志,
     * 避免 `/api/logs/stream` 的同步 `PicoLog` 订阅者发生递归栈溢出。
     *
     * @return true 已入队;false 连接已关闭或已因队列溢出被关闭(本轮未投递)。
     */
    fun sendText(text: String): Boolean {
        var overflowed = false
        queueLock.lock()
        try {
            if (closed) return false
            if (pending.size >= PENDING_CAPACITY) {
                closed = true
                pending.clear()
                notEmpty.signalAll()
                overflowed = true
            } else {
                pending.addLast(text)
                notEmpty.signal()
                return true
            }
        } finally {
            queueLock.unlock()
        }
        // 先触发后台关闭再打日志,并且 closed 已在锁内原子置位,
        // 即使 PicoLog 同步回调重入本连接的 sendText 也会立刻返回 false。
        if (overflowed) {
            val total = droppedEvents.incrementAndGet()
            closeAsync(1008)
            PicoLog.w("ws send queue overflow on $tag; closing slow peer (totalOverflow=$total)")
        }
        return false
    }

    /** 把一个可能阻塞的 [close] 挪到后台线程;重复调用只生效一次。 */
    internal fun closeAsync(code: Int = 1000) {
        queueLock.lock()
        try {
            if (!closed) {
                closed = true
                pending.clear()
                notEmpty.signalAll()
            }
        } finally {
            queueLock.unlock()
        }
        if (!closeRequested.compareAndSet(false, true)) return
        try {
            Thread({ close(code) }, "pico-ws-close").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
            // 极端 OOM 下无法新建线程时直接关 socket 兜底,绝不泄漏 FD。
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            writer.interrupt()
        }
    }

    private fun writeLoop() {
        var nextPingAt = System.nanoTime() + PING_INTERVAL_NS
        try {
            while (!closed) {
                val now = System.nanoTime()
                if ((now - nextPingAt) >= 0L) {
                    if (closed) return
                    if (onPingCheck != null && !onPingCheck.invoke()) {
                        close(1008)
                        return
                    }
                    if (!writeFrame(WsFrame.OP_PING, ByteArray(0))) return
                    nextPingAt = System.nanoTime() + PING_INTERVAL_NS
                }
                var text: String? = null
                queueLock.lock()
                try {
                    while (!closed && pending.isEmpty()) {
                        val waitNs = nextPingAt - System.nanoTime()
                        if (waitNs <= 0L) break
                        notEmpty.awaitNanos(waitNs)
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
            Thread.currentThread().interrupt()
        }
    }

    private fun writeFrame(opcode: Int, payload: ByteArray): Boolean {
        return try {
            writeLock.lock()
            try {
                if (closed) return false
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

    fun close() = close(1000)

    fun close(code: Int) {
        queueLock.lock()
        try {
            if (!closed) {
                closed = true
                pending.clear()
                notEmpty.signalAll()
            }
        } finally {
            queueLock.unlock()
        }
        if (!teardownStarted.compareAndSet(false, true)) return
        // 尽力发一个 CLOSE 帧,让对端按 RFC 6455 状态码感知关闭原因。
        // 必须 tryLock 且限时:慢连接阻塞在写锁上时直接跳过 CLOSE 帧关 socket。
        try {
            if (writeLock.tryLock(CLOSE_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                try {
                    WsFrame.write(out, WsFrame.OP_CLOSE, closePayload(code), maskOut)
                } finally {
                    writeLock.unlock()
                }
            }
        } catch (_: Throwable) {
        }
        try {
            socket.close()
        } catch (_: Throwable) {
        }
        writer.interrupt()
    }

    /** RFC 6455 §5.5.1:关闭帧负载是 2 字节大端状态码。 */
    private fun closePayload(code: Int): ByteArray =
        byteArrayOf(((code shr 8) and 0xFF).toByte(), (code and 0xFF).toByte())

    private fun decodeUtf8Strict(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (_: CharacterCodingException) {
            throw WsFrame.ProtocolException(1007, "protocol error: invalid UTF-8 payload")
        }
    }

    /** RFC 6455 §5.5.1 / §7.4.1: 校验对端 CLOSE 帧的状态码合法范围及 UTF-8 reason。 */
    private fun validateClosePayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        if (payload.size == 1) {
            throw WsFrame.ProtocolException(1002, "protocol error: close frame payload length 1")
        }
        val peerCode = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (!WsFrame.isValidCloseCode(peerCode)) {
            throw WsFrame.ProtocolException(1002, "protocol error: invalid close status code $peerCode")
        }
        if (payload.size > 2) {
            decodeUtf8Strict(payload, 2, payload.size - 2)
        }
    }

    /**
     * 阻塞读循环:处理分片、ping/pong、close;每条完整文本回调一次。
     * 返回即代表连接已结束。
     */
    fun loop(onText: (String) -> Unit) {
        var acc = ByteArrayOutputStream()
        var accOpcode = -1
        var accTotalBytes = 0L
        var closeCode = 1000
        // maskOut=true 说明本端是反向 WS 的客户端,读的是服务端帧(不得带掩码);
        // 否则本端是服务端,读客户端帧时必须带掩码。
        val requireMask = !maskOut
        try {
            while (!closed) {
                val f = WsFrame.read(ins, requireMask)
                when (f.opcode) {
                    WsFrame.OP_PING -> if (!writeFrame(WsFrame.OP_PONG, f.payload)) return
                    WsFrame.OP_PONG -> Unit
                    WsFrame.OP_CLOSE -> {
                        validateClosePayload(f.payload)
                        return
                    }
                    WsFrame.OP_TEXT, WsFrame.OP_BIN -> {
                        if (accOpcode != -1) {
                            throw WsFrame.ProtocolException(1002, "protocol error: new data frame while fragment open")
                        }
                        if (f.fin) {
                            if (f.opcode == WsFrame.OP_TEXT) {
                                onText(decodeUtf8Strict(f.payload))
                            }
                        } else {
                            if (f.payload.size.toLong() > WsFrame.MAX_MESSAGE) {
                                throw WsFrame.ProtocolException(1009, "protocol error: message exceeds " + WsFrame.MAX_MESSAGE)
                            }
                            accOpcode = f.opcode
                            accTotalBytes = f.payload.size.toLong()
                            if (f.opcode == WsFrame.OP_TEXT) {
                                acc.reset()
                                acc.write(f.payload)
                            }
                        }
                    }
                    WsFrame.OP_CONT -> {
                        if (accOpcode == -1) {
                            throw WsFrame.ProtocolException(1002, "protocol error: continuation without start")
                        }
                        val nextTotal = accTotalBytes + f.payload.size.toLong()
                        if (nextTotal > WsFrame.MAX_MESSAGE) {
                            throw WsFrame.ProtocolException(1009, "protocol error: fragmented message exceeds " + WsFrame.MAX_MESSAGE)
                        }
                        accTotalBytes = nextTotal
                        if (accOpcode == WsFrame.OP_TEXT) {
                            acc.write(f.payload)
                        }
                        if (f.fin) {
                            val finishedOpcode = accOpcode
                            val payload = if (finishedOpcode == WsFrame.OP_TEXT) acc.toByteArray() else null
                            if (acc.size() > ACC_SHRINK_THRESHOLD) {
                                acc = ByteArrayOutputStream()
                            } else {
                                acc.reset()
                            }
                            accOpcode = -1
                            accTotalBytes = 0L
                            if (payload != null) {
                                onText(decodeUtf8Strict(payload))
                            }
                        }
                    }
                }
            }
        } catch (e: WsFrame.ProtocolException) {
            closeCode = e.closeCode
            PicoLog.d("ws protocol error on $tag (code=${e.closeCode}): ${e.message}")
        } catch (t: Throwable) {
            PicoLog.d("ws loop end on $tag: " + t.javaClass.simpleName + " " + t.message)
        } finally {
            close(closeCode)
        }
    }
}
