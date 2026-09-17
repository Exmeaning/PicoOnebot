package pico.onebot.net

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Random

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

    private val rng = Random()

    class Frame(val opcode: Int, val payload: ByteArray, val fin: Boolean)

    @Throws(IOException::class)
    fun read(ins: InputStream): Frame {
        val b0 = ins.read()
        if (b0 < 0) throw EOFException("stream closed")
        val b1 = readByte(ins)
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((readByte(ins).toLong() shl 8) or readByte(ins).toLong())
        } else if (len == 127L) {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or readByte(ins).toLong()
            len = v
        }
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("frame too large: $len")
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

    /** 读一行(以 \n 结束,去掉 \r);流结束返回 null。 */
    @Throws(IOException::class)
    fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}
