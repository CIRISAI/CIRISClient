package ai.ciris.mobile.shared.platform.util

/**
 * QR Code model 2 encoder — pure Kotlin, written from ISO/IEC 18004:2015.
 *
 * WHY NOT A LIBRARY. The candidates were weighed against this module's pins,
 * not against a blank project. The compiler is Kotlin 2.0.21 and stdlib is
 * forced to it (build.gradle.kts: a dependency carrying newer metadata crashes
 * the FIR checker, which is what yubikit did), and the wasmJs target reads
 * klibs whose ABI is not stable across Kotlin releases. Checked 2026-09 on
 * Maven Central: qrose 1.3.0 (MIT) requires kotlin-stdlib 2.4.0 and Compose
 * UI 1.12.0; its last release on our line, 1.0.1, is a Kotlin 1.9.22 build
 * from 2024 whose wasm klib predates 2.0. qrcode-kotlin 4.5.0 (MIT) is a
 * Kotlin 2.1.21 build, a klib ABI ahead of this compiler. So pinning one
 * means either a newer toolchain than this module has or an unmaintained
 * release. The algorithm is a closed, published standard
 * and a few hundred lines; owning it costs less than either. What makes it
 * trustworthy is not that it is short but that `QrEncoderTest` (desktopTest)
 * decodes what it draws with zxing-core — an independent decoder — at every
 * version 1..40 and every error-correction level.
 *
 * Scope: one segment per symbol, in the most compact of numeric, alphanumeric
 * or byte mode that holds the whole string; byte mode is UTF-8, and says so
 * with an ECI 26 header when the text is not plain ASCII (a scanner otherwise
 * guesses Latin-1). No Kanji mode, no structured append, no micro QR.
 *
 * The output is a [QrMatrix]; drawing it is `ui/primitives/QrCode.kt`'s job.
 */
object QrEncoder {

    /** Error-correction level. [formatBits] is the two-bit field ISO 18004 table 12 assigns. */
    enum class Ecc(internal val ordinalInTables: Int, internal val formatBits: Int) {
        /** ~7% of codewords recoverable. */
        L(0, 1),
        /** ~15% — the level the product uses: a phone photo of a phone screen survives it. */
        M(1, 0),
        /** ~25%. */
        Q(2, 3),
        /** ~30%. */
        H(3, 2),
    }

    const val MIN_VERSION = 1
    const val MAX_VERSION = 40

    /**
     * Encode [text] at [ecc], choosing the smallest version that holds it.
     *
     * @throws QrTooLongException when no version up to 40 holds it at [ecc].
     */
    fun encode(text: String, ecc: Ecc = Ecc.M): QrMatrix =
        encode(text, ecc, minVersion = MIN_VERSION)

    /** [encode], or null when the text does not fit in a version-40 symbol. */
    fun encodeOrNull(text: String, ecc: Ecc = Ecc.M): QrMatrix? =
        try { encode(text, ecc) } catch (_: QrTooLongException) { null }

    /**
     * As [encode] but never smaller than [minVersion]. Tests use this to force
     * every version through the layout code; the product has no reason to.
     */
    internal fun encode(text: String, ecc: Ecc, minVersion: Int): QrMatrix {
        require(minVersion in MIN_VERSION..MAX_VERSION) { "version $minVersion outside 1..40" }
        val segment = Segment.of(text)
        var version = minVersion
        while (true) {
            val used = segment.bitLength(version)
            if (used != null && used <= dataCodewords(version, ecc) * 8) break
            if (version == MAX_VERSION) {
                throw QrTooLongException(
                    "${text.length} characters do not fit a version-40 QR code at level $ecc",
                )
            }
            version++
        }
        val data = dataCodewordsFor(segment, version, ecc)
        val codewords = addEccAndInterleave(data, version, ecc)
        return Builder(version, ecc).build(codewords)
    }

    // ─── Segment: mode, character count and payload bits ────────────────────

    private enum class Mode(val indicator: Int, val countBits: IntArray) {
        NUMERIC(0x1, intArrayOf(10, 12, 14)),
        ALPHANUMERIC(0x2, intArrayOf(9, 11, 13)),
        BYTE(0x4, intArrayOf(8, 16, 16));

        /** Bits of the character-count field; the width steps at versions 10 and 27. */
        fun countBitsAt(version: Int): Int = countBits[
            when {
                version <= 9 -> 0
                version <= 26 -> 1
                else -> 2
            },
        ]
    }

    private const val ALPHANUMERIC_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
    private const val ECI_MODE = 0x7
    private const val ECI_UTF8 = 26

    private class Segment(val mode: Mode, val count: Int, val eci: Int?, val bits: BitBuffer) {
        /** Total bits at [version], or null if the count overflows its field. */
        fun bitLength(version: Int): Int? {
            val cb = mode.countBitsAt(version)
            if (count >= (1 shl cb)) return null
            val eciBits = if (eci != null) 4 + 8 else 0
            return eciBits + 4 + cb + bits.size
        }

        companion object {
            fun of(text: String): Segment = when {
                text.all { it in '0'..'9' } -> numeric(text)
                text.all { ALPHANUMERIC_CHARSET.indexOf(it) >= 0 } -> alphanumeric(text)
                else -> bytes(text)
            }

            private fun numeric(text: String): Segment {
                val bb = BitBuffer()
                var i = 0
                while (i < text.length) {
                    val n = minOf(3, text.length - i)
                    bb.append(text.substring(i, i + n).toInt(), n * 3 + 1)
                    i += n
                }
                return Segment(Mode.NUMERIC, text.length, null, bb)
            }

            private fun alphanumeric(text: String): Segment {
                val bb = BitBuffer()
                var i = 0
                while (i + 1 < text.length) {
                    val v = ALPHANUMERIC_CHARSET.indexOf(text[i]) * 45 +
                        ALPHANUMERIC_CHARSET.indexOf(text[i + 1])
                    bb.append(v, 11)
                    i += 2
                }
                if (i < text.length) bb.append(ALPHANUMERIC_CHARSET.indexOf(text[i]), 6)
                return Segment(Mode.ALPHANUMERIC, text.length, null, bb)
            }

            private fun bytes(text: String): Segment {
                val utf8 = text.encodeToByteArray()
                val bb = BitBuffer()
                for (b in utf8) bb.append(b.toInt() and 0xFF, 8)
                // ECI only when it changes the reading: plain ASCII decodes the
                // same under the Latin-1 default every scanner assumes.
                val eci = if (text.any { it.code > 0x7F }) ECI_UTF8 else null
                return Segment(Mode.BYTE, utf8.size, eci, bb)
            }
        }
    }

    private fun dataCodewordsFor(segment: Segment, version: Int, ecc: Ecc): ByteArray {
        val capacityBits = dataCodewords(version, ecc) * 8
        val bb = BitBuffer()
        segment.eci?.let {
            bb.append(ECI_MODE, 4)
            bb.append(it, 8) // designators < 128 take the one-byte form 0xxxxxxx
        }
        bb.append(segment.mode.indicator, 4)
        bb.append(segment.count, segment.mode.countBitsAt(version))
        bb.appendAll(segment.bits)
        check(bb.size <= capacityBits)
        // Terminator (up to four zero bits), then to a byte boundary, then the
        // alternating pad codewords 0xEC 0x11 (ISO 18004 §7.4.10).
        bb.append(0, minOf(4, capacityBits - bb.size))
        bb.append(0, (8 - bb.size % 8) % 8)
        var pad = 0xEC
        while (bb.size < capacityBits) {
            bb.append(pad, 8)
            pad = pad xor (0xEC xor 0x11)
        }
        return bb.toBytes()
    }

    // ─── Capacity tables (ISO 18004 table 9) ─────────────────────────────────

    /** Error-correction codewords per block, indexed [ecc][version]. */
    private val ECC_CODEWORDS_PER_BLOCK: Array<IntArray> = arrayOf(
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )

    /** Error-correction blocks, indexed [ecc][version]. */
    private val ECC_BLOCKS: Array<IntArray> = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )

    /** Modules left for data + EC after every function pattern is placed (remainder bits included). */
    private fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    /** Data codewords a symbol of [version] at [ecc] carries. */
    internal fun dataCodewords(version: Int, ecc: Ecc): Int =
        rawDataModules(version) / 8 -
            ECC_CODEWORDS_PER_BLOCK[ecc.ordinalInTables][version] * ECC_BLOCKS[ecc.ordinalInTables][version]

    // ─── Reed–Solomon over GF(2^8), primitive polynomial 0x11D ───────────────

    private fun addEccAndInterleave(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
        val numBlocks = ECC_BLOCKS[ecc.ordinalInTables][version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.ordinalInTables][version]
        val rawCodewords = rawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks

        val divisor = rsDivisor(blockEccLen)
        val blocks = ArrayList<ByteArray>(numBlocks)
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + datLen)
            k += datLen
            val ecc = rsRemainder(dat, divisor)
            // Short blocks get one placeholder byte so every block has the same
            // length; the placeholder is skipped when interleaving.
            val block = ByteArray(shortBlockLen + 1)
            dat.copyInto(block, 0)
            ecc.copyInto(block, block.size - blockEccLen)
            blocks.add(block)
        }
        check(k == data.size)

        val result = ByteArray(rawCodewords)
        var n = 0
        for (i in 0..shortBlockLen) {
            for (j in 0 until numBlocks) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[n++] = blocks[j][i]
                }
            }
        }
        check(n == rawCodewords)
        return result
    }

    private fun rsDivisor(degree: Int): IntArray {
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
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    // ─── Module placement ────────────────────────────────────────────────────

    private class Builder(private val version: Int, private val ecc: Ecc) {
        val size = version * 4 + 17
        private val modules = BooleanArray(size * size)
        private val isFunction = BooleanArray(size * size)

        private fun get(x: Int, y: Int) = modules[y * size + x]
        private fun set(x: Int, y: Int, dark: Boolean) { modules[y * size + x] = dark }
        private fun setFunction(x: Int, y: Int, dark: Boolean) {
            set(x, y, dark)
            isFunction[y * size + x] = true
        }

        fun build(codewords: ByteArray): QrMatrix {
            drawFunctionPatterns()
            drawCodewords(codewords)
            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormatBits(mask)
                val p = penalty()
                if (p < bestPenalty) {
                    bestPenalty = p
                    bestMask = mask
                }
                applyMask(mask) // XOR is its own inverse
            }
            applyMask(bestMask)
            drawFormatBits(bestMask)
            return QrMatrix(version, size, modules.copyOf())
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                setFunction(6, i, i % 2 == 0)
                setFunction(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val align = alignmentPositions()
            val last = align.size - 1
            for (i in align.indices) {
                for (j in align.indices) {
                    val onFinder = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
                    if (!onFinder) drawAlignment(align[i], align[j])
                }
            }
            drawFormatBits(0) // reserve the area; the real bits go in after masking
            drawVersionBits()
        }

        private fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        setFunction(x, y, dist != 2 && dist != 4)
                    }
                }
            }
        }

        private fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) {
                    setFunction(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
        }

        /** Alignment-pattern centre coordinates (ISO 18004 annex E), ascending. */
        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        private fun drawFormatBits(mask: Int) {
            val data = (ecc.formatBits shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) != 0

            // First copy, around the top-left finder.
            for (i in 0..5) setFunction(8, i, bit(i))
            setFunction(8, 7, bit(6))
            setFunction(8, 8, bit(7))
            setFunction(7, 8, bit(8))
            for (i in 9 until 15) setFunction(14 - i, 8, bit(i))
            // Second copy, split between the other two finders.
            for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) setFunction(8, size - 15 + i, bit(i))
            setFunction(8, size - 8, true) // the dark module, always dark
        }

        private fun drawVersionBits() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = ((bits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val b = i / 3
                setFunction(a, b, dark)
                setFunction(b, a, dark)
            }
        }

        private fun drawCodewords(data: ByteArray) {
            var i = 0
            val total = data.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5 // skip the vertical timing column
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y * size + x] && i < total) {
                            set(x, y, ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0)
                            i++
                        }
                        // Remainder bits stay light, which is what zero means.
                    }
                }
                right -= 2
            }
            check(i == total)
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (isFunction[y * size + x]) continue
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                        else -> error("mask $mask")
                    }
                    if (invert) set(x, y, !get(x, y))
                }
            }
        }

        /** ISO 18004 §7.8.3 penalty; lower is better. Only its ORDER matters. */
        private fun penalty(): Int {
            var result = 0
            // N1 — runs of five or more same-coloured modules, rows then columns.
            for (horizontal in listOf(true, false)) {
                for (a in 0 until size) {
                    var runColor = false
                    var run = 0
                    for (b in 0 until size) {
                        val c = if (horizontal) get(b, a) else get(a, b)
                        if (b > 0 && c == runColor) {
                            run++
                        } else {
                            if (run >= 5) result += 3 + (run - 5)
                            runColor = c
                            run = 1
                        }
                    }
                    if (run >= 5) result += 3 + (run - 5)
                }
            }
            // N2 — 2x2 blocks of one colour.
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val c = get(x, y)
                    if (c == get(x + 1, y) && c == get(x, y + 1) && c == get(x + 1, y + 1)) result += 3
                }
            }
            // N3 — finder-like 1:1:3:1:1 with four light modules on one side.
            for (horizontal in listOf(true, false)) {
                for (a in 0 until size) {
                    for (b in 0..size - 11) {
                        fun m(k: Int) = if (horizontal) get(b + k, a) else get(a, b + k)
                        val core = m(0) && !m(1) && m(2) && m(3) && m(4) && !m(5) && m(6)
                        if (core && !m(7) && !m(8) && !m(9) && !m(10)) result += 40
                        val coreR = !m(0) && !m(1) && !m(2) && !m(3) &&
                            m(4) && !m(5) && m(6) && m(7) && m(8) && !m(9) && m(10)
                        if (coreR) result += 40
                    }
                }
            }
            // N4 — distance of the dark proportion from 50%, per 5%.
            val dark = modules.count { it }
            val total = size * size
            val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += maxOf(0, k) * 10
            return result
        }
    }

    // ─── Bits ────────────────────────────────────────────────────────────────

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size: Int get() = bits.size

        fun append(value: Int, length: Int) {
            require(length in 0..31 && value ushr length == 0) { "value $value does not fit $length bits" }
            for (i in length - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }

        fun appendAll(other: BitBuffer) { bits.addAll(other.bits) }

        fun toBytes(): ByteArray {
            check(bits.size % 8 == 0)
            return ByteArray(bits.size / 8) { i ->
                var v = 0
                for (j in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + j]) 1 else 0)
                v.toByte()
            }
        }
    }
}

/**
 * A finished symbol: [size]×[size] modules, true = dark. Carries NO quiet
 * zone — the renderer adds the four light modules ISO 18004 §6.3.8 requires.
 */
class QrMatrix internal constructor(
    val version: Int,
    val size: Int,
    private val modules: BooleanArray,
) {
    /** Is the module at column [x], row [y] dark? */
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

/** The text is longer than a version-40 symbol holds at the requested level. */
class QrTooLongException(message: String) : IllegalArgumentException(message)
