package pico.onebot.core

/**
 * 极简 QR 编码器:仅 Byte 模式、纠错等级 L、版本 1–9(最多 230 字节)。
 * 只为"把控制台地址画成二维码"服务 —— 宿主里的 ZXing 编码器被混淆成单字母,跨版本不可靠。
 * 算法按 ISO/IEC 18004;结构参考 nayuki 的 QR-Code-generator。
 */
object QrEncoder {

    class Symbol(val size: Int, private val modules: Array<BooleanArray>) {
        fun dark(row: Int, col: Int): Boolean = modules[row][col]
    }

    // 版本 1..9、等级 L:每块数据码字数 / 块数 / 每块纠错码字数(这些版本的 L 各块等长,不需要第二组)
    private val DATA_PER_BLOCK = intArrayOf(0, 19, 34, 55, 80, 108, 68, 78, 97, 116)
    private val BLOCKS = intArrayOf(0, 1, 1, 1, 1, 1, 2, 2, 2, 2)
    private val EC_PER_BLOCK = intArrayOf(0, 7, 10, 15, 20, 26, 18, 20, 24, 30)

    fun encode(text: String): Symbol {
        val data = text.toByteArray(Charsets.UTF_8)
        var version = 1
        while (version <= 9 && DATA_PER_BLOCK[version] * BLOCKS[version] - 2 < data.size) version++
        require(version <= 9) { "text too long for QR v9-L: " + data.size + " bytes" }
        val dataCap = DATA_PER_BLOCK[version] * BLOCKS[version]

        // 位流:模式(4) + 长度(8) + 数据 + 终止符 + 字节对齐 + 填充
        val bits = BitBuf()
        bits.put(0b0100, 4)
        bits.put(data.size, 8)
        for (b in data) bits.put(b.toInt() and 0xFF, 8)
        val capBits = dataCap * 8
        bits.put(0, minOf(4, capBits - bits.len))
        while (bits.len % 8 != 0) bits.put(0, 1)
        var pad = 0xEC
        while (bits.len < capBits) {
            bits.put(pad, 8)
            pad = if (pad == 0xEC) 0x11 else 0xEC
        }
        val codewords = interleave(bits.toBytes(), version)

        val size = version * 4 + 17
        val m = Matrix(size)
        m.drawFunctionPatterns(version)
        m.drawCodewords(codewords)
        var best = 0
        var bestScore = Int.MAX_VALUE
        for (mask in 0 until 8) {
            m.applyMask(mask)
            m.drawFormat(mask)
            val s = m.penalty()
            if (s < bestScore) {
                bestScore = s
                best = mask
            }
            m.applyMask(mask) // 异或两次 = 撤销
        }
        m.applyMask(best)
        m.drawFormat(best)
        return Symbol(size, m.modules)
    }

    // ---------------- 位流 ----------------

    private class BitBuf {
        private val bits = ArrayList<Boolean>()
        val len: Int get() = bits.size

        fun put(value: Int, n: Int) {
            for (i in n - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }

        fun toBytes(): ByteArray {
            val out = ByteArray(bits.size / 8)
            for (i in bits.indices) if (bits[i]) {
                out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            }
            return out
        }
    }

    // ---------------- Reed-Solomon(GF(256), 0x11D) ----------------

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    private fun rsGenerator(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    private fun interleave(data: ByteArray, version: Int): ByteArray {
        val nb = BLOCKS[version]
        val dl = DATA_PER_BLOCK[version]
        val el = EC_PER_BLOCK[version]
        val gen = rsGenerator(el)
        val blocks = Array(nb) { b -> data.copyOfRange(b * dl, (b + 1) * dl) }
        val ecs = Array(nb) { b -> rsRemainder(blocks[b], gen) }
        val out = ByteArray(nb * (dl + el))
        var k = 0
        for (i in 0 until dl) for (b in 0 until nb) out[k++] = blocks[b][i]
        for (i in 0 until el) for (b in 0 until nb) out[k++] = ecs[b][i]
        return out
    }

    // ---------------- 矩阵 ----------------

    private class Matrix(val size: Int) {
        val modules = Array(size) { BooleanArray(size) }
        private val isFunc = Array(size) { BooleanArray(size) }

        private fun setFunc(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunc[y][x] = true
        }

        fun drawFunctionPatterns(version: Int) {
            for (i in 0 until size) {
                setFunc(6, i, i % 2 == 0)
                setFunc(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val pos = alignPositions(version)
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                align(pos[i], pos[j])
            }
            drawFormat(0) // 先占位,选好掩码后重画
            drawVersion(version)
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(Math.abs(dx), Math.abs(dy))
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) setFunc(x, y, d != 2 && d != 4)
            }
        }

        private fun align(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) setFunc(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        private fun alignPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val num = version / 7 + 2
            val step = (version * 8 + num * 3 + 5) / (num * 4 - 4) * 2
            val res = IntArray(num)
            res[0] = 6
            var p = size - 7
            for (i in num - 1 downTo 1) {
                res[i] = p
                p -= step
            }
            return res
        }

        fun drawFormat(mask: Int) {
            val data = (1 shl 3) or mask // 等级 L = 01
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) setFunc(8, i, bit(i))
            setFunc(8, 7, bit(6))
            setFunc(8, 8, bit(7))
            setFunc(7, 8, bit(8))
            for (i in 9 until 15) setFunc(14 - i, 8, bit(i))
            for (i in 0 until 8) setFunc(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) setFunc(8, size - 15 + i, bit(i))
            setFunc(8, size - 8, true)
        }

        private fun drawVersion(version: Int) {
            if (version < 7) return
            var rem = version
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = ((bits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val c = i / 3
                setFunc(a, c, b)
                setFunc(c, a, b)
            }
        }

        fun drawCodewords(data: ByteArray) {
            var i = 0
            val total = data.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunc[y][x] && i < total) {
                        modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (isFunc[y][x]) continue
                val inv = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (inv) modules[y][x] = !modules[y][x]
            }
        }

        /** 掩码评分(规则 1/2/4;够用 —— 任何掩码都能被解码,评分只影响易扫程度)。 */
        fun penalty(): Int {
            var res = 0
            for (y in 0 until size) {
                var run = 0
                var color = false
                for (x in 0 until size) {
                    if (modules[y][x] == color) {
                        run++
                        if (run == 5) res += 3 else if (run > 5) res++
                    } else {
                        color = modules[y][x]
                        run = 1
                    }
                }
            }
            for (x in 0 until size) {
                var run = 0
                var color = false
                for (y in 0 until size) {
                    if (modules[y][x] == color) {
                        run++
                        if (run == 5) res += 3 else if (run > 5) res++
                    } else {
                        color = modules[y][x]
                        run = 1
                    }
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) res += 3
            }
            var dark = 0
            for (row in modules) for (b in row) if (b) dark++
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) / total - 1).coerceAtLeast(0)
            res += k * 10
            return res
        }
    }
}
