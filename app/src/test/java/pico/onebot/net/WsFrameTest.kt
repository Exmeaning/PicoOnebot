package pico.onebot.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

class WsFrameTest {

    @Test
    fun readLineParsesCrLfLfAndEof() {
        val input = "GET / HTTP/1.1\r\nHost: localhost\nLastLine".toByteArray(StandardCharsets.ISO_8859_1)
        val stream = ByteArrayInputStream(input)

        assertEquals("GET / HTTP/1.1", WsFrame.readLine(stream))
        assertEquals("Host: localhost", WsFrame.readLine(stream))
        assertEquals("LastLine", WsFrame.readLine(stream))
        assertNull(WsFrame.readLine(stream))
    }

    @Test
    fun readLineEnforces8KiBCapRejectsBareCrAndEnforcesDeadline() {
        assertEquals(8 * 1024, WsFrame.MAX_LINE_BYTES)

        // Exact 8192 bytes before LF succeeds
        val maxLf = "a".repeat(WsFrame.MAX_LINE_BYTES) + "\n"
        assertEquals(
            WsFrame.MAX_LINE_BYTES,
            WsFrame.readLine(ByteArrayInputStream(maxLf.toByteArray(StandardCharsets.ISO_8859_1)))?.length
        )

        // 8193 bytes exceeds MAX_LINE_BYTES -> LineTooLongException
        val overLimit = "a".repeat(WsFrame.MAX_LINE_BYTES + 1) + "\n"
        try {
            WsFrame.readLine(ByteArrayInputStream(overLimit.toByteArray(StandardCharsets.ISO_8859_1)))
            fail("Expected LineTooLongException for >8 KiB line")
        } catch (_: WsFrame.LineTooLongException) {
            // expected
        }

        // Bare CR inside line is rejected
        try {
            WsFrame.readLine(ByteArrayInputStream("bad\rline\n".toByteArray(StandardCharsets.ISO_8859_1)))
            fail("Expected IOException for bare CR")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("bare CR"))
        }

        // Truncated CR at EOF is rejected
        try {
            WsFrame.readLine(ByteArrayInputStream("eof\r".toByteArray(StandardCharsets.ISO_8859_1)))
            fail("Expected IOException for truncated CR")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("truncated"))
        }

        // Expired monotonic deadline throws SocketTimeoutException
        try {
            WsFrame.readLine(
                ByteArrayInputStream("hello\n".toByteArray(StandardCharsets.ISO_8859_1)),
                deadlineNanos = System.nanoTime() - 1_000_000L
            )
            fail("Expected SocketTimeoutException when deadline has passed")
        } catch (_: SocketTimeoutException) {
            // expected
        }
    }

    @Test
    fun readValidatesRfc6455RsvControlOpcodesAndMaskBits() {
        fun assertProtocolError(expectedCloseCode: Int, bytes: ByteArray, requireMask: Boolean = false) {
            try {
                WsFrame.read(ByteArrayInputStream(bytes), requireMask = requireMask)
                fail("Expected WsFrame.ProtocolException($expectedCloseCode)")
            } catch (e: WsFrame.ProtocolException) {
                assertEquals(expectedCloseCode, e.closeCode)
            }
        }

        // Non-zero RSV1/RSV2/RSV3 bits
        assertProtocolError(1002, byteArrayOf((0x80 or 0x40 or WsFrame.OP_TEXT).toByte(), 0x00))
        assertProtocolError(1002, byteArrayOf((0x80 or 0x20 or WsFrame.OP_TEXT).toByte(), 0x00))
        assertProtocolError(1002, byteArrayOf((0x80 or 0x10 or WsFrame.OP_TEXT).toByte(), 0x00))

        // Reserved data and control opcodes
        assertProtocolError(1002, byteArrayOf((0x80 or 0x03).toByte(), 0x00))
        assertProtocolError(1002, byteArrayOf((0x80 or 0x0B).toByte(), 0x00))

        // Fragmented control frame (FIN=0 on PING)
        assertProtocolError(1002, byteArrayOf(WsFrame.OP_PING.toByte(), 0x00))

        // Control frame with extended length 126 (>125 bytes)
        assertProtocolError(1002, byteArrayOf((0x80 or WsFrame.OP_PING).toByte(), 126, 0x00, 126))

        // Close frame with 1-byte payload (RFC 6455 requires 0 or >=2)
        assertProtocolError(1002, byteArrayOf((0x80 or WsFrame.OP_CLOSE).toByte(), 0x01, 0x03))

        // Mask mismatch (client->server unmasked or server->client masked)
        assertProtocolError(1002, byteArrayOf((0x80 or WsFrame.OP_TEXT).toByte(), 0x00), requireMask = true)
        assertProtocolError(
            1002,
            byteArrayOf((0x80 or WsFrame.OP_TEXT).toByte(), 0x80.toByte(), 1, 2, 3, 4),
            requireMask = false
        )

        // Frame payload exceeding MAX_PAYLOAD (32 MiB) -> close code 1009
        val hugeLen = (32L * 1024L * 1024L) + 1L
        val hugeHeader = ByteArray(10)
        hugeHeader[0] = (0x80 or WsFrame.OP_BIN).toByte()
        hugeHeader[1] = 127
        for (i in 0 until 8) {
            hugeHeader[2 + i] = ((hugeLen ushr (56 - i * 8)) and 0xFF).toByte()
        }
        assertProtocolError(1009, hugeHeader)
    }

    @Test
    fun maskingAndUnmaskedRoundTripAcrossSmallMediumAndLargePayloads() {
        val payloads = listOf(
            WsFrame.OP_TEXT to ByteArray(0),
            WsFrame.OP_TEXT to "hello pico onebot".toByteArray(StandardCharsets.UTF_8),
            WsFrame.OP_PING to "ping-123".toByteArray(StandardCharsets.UTF_8),
            WsFrame.OP_BIN to ByteArray(300) { (it * 7 and 0xFF).toByte() },
            WsFrame.OP_BIN to ByteArray(70_000) { (it * 13 and 0xFF).toByte() }
        )

        for (masked in listOf(true, false)) {
            for ((opcode, payload) in payloads) {
                val out = ByteArrayOutputStream()
                WsFrame.write(out, opcode, payload, mask = masked)
                val wire = out.toByteArray()

                val maskBitSet = (wire[1].toInt() and 0x80) != 0
                assertEquals(masked, maskBitSet)

                val decoded = WsFrame.read(ByteArrayInputStream(wire), requireMask = masked)
                assertTrue(decoded.fin)
                assertEquals(opcode, decoded.opcode)
                assertArrayEquals(payload, decoded.payload)
            }
        }
    }
}
