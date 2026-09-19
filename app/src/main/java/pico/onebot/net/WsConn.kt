package pico.onebot.net

import pico.onebot.core.PicoLog
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue

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

    private val writeLock = Object()
    private val pending = ArrayBlockingQueue<String>(256)
    private val writer = Thread({ writeLoop() }, "pico-ws-send").apply { isDaemon = true }

    @Volatile
    var closed = false
        private set

    init {
        writer.start()
    }

    val wantsEvents: Boolean get() = role == Role.EVENT || role == Role.BOTH
    val wantsActions: Boolean get() = role == Role.API || role == Role.BOTH

    fun sendText(text: String): Boolean {
        if (closed) return false
        if (pending.offer(text)) return true
        PicoLog.w("ws send queue full on $tag; disconnecting slow peer")
        close()
        return false
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val text = pending.take()
                if (!writeText(text)) return
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun writeText(text: String): Boolean {
        return try {
            synchronized(writeLock) {
                WsFrame.write(out, WsFrame.OP_TEXT, text.toByteArray(Charsets.UTF_8), maskOut)
            }
            true
        } catch (t: Throwable) {
            PicoLog.w("ws send failed on $tag: " + t.message)
            close()
            false
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            socket.close()
        } catch (ignored: Throwable) {
        }
        writer.interrupt()
        pending.clear()
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
                    WsFrame.OP_PING -> synchronized(writeLock) {
                        WsFrame.write(out, WsFrame.OP_PONG, f.payload, maskOut)
                    }
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
