package pico.onebot.net

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * RFC 6455 帧编解码。自带实现,不引第三方库 ——
 * 理由见 docs/PicoOneBot-架构与流程.md §8(ApkMixin 合并期的同名类冲突)。
 */
object WsFrame {

    const val OP_CONT = 0x0
    const val OP_TEXT = 0x1
    const val OP_BIN = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    private const val MAX_PAYLOAD = 32L * 1024 * 1024
    private const val MAX_CONTROL_PAYLOAD = 125

    /**
     * 单条消息(分片累积后)的上限。`MAX_PAYLOAD` 只约束**单帧**,
     * 对端持续发 CONT 帧就能无限占堆 —— 安卓堆只有几百兆,撑爆就是把宿主 QQ 带崩。
     */
    const val MAX_MESSAGE = 32L * 1024 * 1024

    /** 单行 HTTP 请求行 / Header / Chunk 头的硬上限,防未鉴权无换行 OOM DoS。 */
    const val MAX_LINE_BYTES = 8 * 1024

    private val rng = SecureRandom()

    class Frame(val opcode: Int, val payload: ByteArray, val fin: Boolean)

    class ProtocolException(val closeCode: Int, message: String) : IOException(message)

    class LineTooLongException(message: String) : IOException(message)

    private fun isValidOpcode(opcode: Int): Boolean = when (opcode) {
        OP_CONT, OP_TEXT, OP_BIN, OP_CLOSE, OP_PING, OP_PONG -> true
        else -> false
    }

    /**
     * @param requireMask 读到的帧是否**必须**带掩码位。
     *   服务端读客户端帧时必须为 true(RFC 6455 §5.1),否则不符合规范的对端
     *   会被静默当成正常连接用下去;客户端读服务端帧时必须为 false,且不得带掩码。
     */
    @Throws(IOException::class)
    fun read(ins: InputStream, requireMask: Boolean = false): Frame {
        val b0 = ins.read()
        if (b0 < 0) throw EOFException("stream closed")
        val b1 = readByte(ins)
        if ((b0 and 0x70) != 0) {
            throw ProtocolException(1002, "protocol error: non-zero RSV bits")
        }
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        if (!isValidOpcode(opcode)) {
            throw ProtocolException(1002, "protocol error: reserved opcode $opcode")
        }
        val isControl = (opcode and 0x08) != 0
        if (isControl && !fin) {
            throw ProtocolException(1002, "protocol error: fragmented control frame")
        }
        val masked = (b1 and 0x80) != 0
        if (requireMask && !masked) {
            throw ProtocolException(1002, "protocol error: client frame without mask")
        }
        if (!requireMask && masked) {
            throw ProtocolException(1002, "protocol error: server frame must not be masked")
        }
        val rawLen = b1 and 0x7F
        if (isControl && rawLen > MAX_CONTROL_PAYLOAD) {
            throw ProtocolException(1002, "protocol error: control frame payload too large")
        }
        var len = rawLen.toLong()
        if (len == 126L) {
            len = ((readByte(ins).toLong() shl 8) or readByte(ins).toLong())
        } else if (len == 127L) {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or readByte(ins).toLong()
            len = v
        }
        if (len < 0) throw ProtocolException(1002, "protocol error: invalid frame length $len")
        if (len > MAX_PAYLOAD) throw ProtocolException(1009, "frame too large: $len")
        if (opcode == OP_CLOSE && len == 1L) {
            throw ProtocolException(1002, "protocol error: close frame payload length 1")
        }
        val mask = if (masked) ByteArray(4).also { readFully(ins, it) } else null
        val data = ByteArray(len.toInt())
        readFully(ins, data)
        if (mask != null) {
            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(opcode, data, fin)
    }

    /** 服务端发帧不加掩码;客户端(反向 WS)必须加掩码。 */
    @Throws(IOException::class)
    fun write(out: OutputStream, opcode: Int, payload: ByteArray, mask: Boolean) {
        val head = ByteArray(2)
        head[0] = (0x80 or opcode).toByte()
        val len = payload.size
        val maskBit = if (mask) 0x80 else 0x00
        when {
            len < 126 -> {
                head[1] = (maskBit or len).toByte()
                out.write(head)
            }
            len <= 0xFFFF -> {
                head[1] = (maskBit or 126).toByte()
                out.write(head)
                out.write(byteArrayOf((len shr 8).toByte(), len.toByte()))
            }
            else -> {
                head[1] = (maskBit or 127).toByte()
                out.write(head)
                val ext = ByteArray(8)
                var v = len.toLong()
                for (i in 7 downTo 0) {
                    ext[i] = (v and 0xFF).toByte()
                    v = v shr 8
                }
                out.write(ext)
            }
        }
        if (mask) {
            val key = ByteArray(4)
            rng.nextBytes(key)
            out.write(key)
            val masked = ByteArray(payload.size)
            for (i in payload.indices) masked[i] = (payload[i].toInt() xor key[i % 4].toInt()).toByte()
            out.write(masked)
        } else {
            out.write(payload)
        }
        out.flush()
    }

    @Throws(IOException::class)
    private fun readByte(ins: InputStream): Int {
        val b = ins.read()
        if (b < 0) throw EOFException("stream closed")
        return b
    }

    @Throws(IOException::class)
    fun readFully(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("stream closed")
            off += n
        }
    }

    /**
     * 读一行(以 \r\n 或 \n 结束,拒绝行中裸 \r 并限制最大长度与可选单调截止时间);流结束返回 null。
     */
    @Throws(IOException::class)
    fun readLine(ins: InputStream, maxLen: Int = MAX_LINE_BYTES, deadlineNanos: Long = 0L): String? {
        val sb = StringBuilder()
        var count = 0
        var prevCr = false
        while (true) {
            if (deadlineNanos > 0L && System.nanoTime() > deadlineNanos) {
                throw SocketTimeoutException("line read deadline exceeded")
            }
            val b = ins.read()
            if (b < 0) {
                if (prevCr) throw IOException("malformed line: truncated after CR")
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (prevCr) {
                if (b == '\n'.code) break
                throw IOException("malformed line: bare CR")
            }
            if (b == '\n'.code) break
            count++
            if (count > maxLen) {
                throw LineTooLongException("line exceeds $maxLen bytes")
            }
            if (b == '\r'.code) {
                prevCr = true
            } else {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }
}
