package ai.ciris.mobile.shared.calls.media

/**
 * A bzip2 decoder, for one job: Cisco publishes its OpenH264 library only as
 * `.bz2`, and the JDK has no bzip2. A library that does would add about 1 MiB to
 * a wheel with 5 MiB of room; this is the decoder alone.
 *
 * What comes out is not trusted because it decoded. [OpenH264Store] compares its
 * SHA-256 with the one Cisco publishes before anything loads it. The checksums
 * here are the format's own, and they catch a decoder that is wrong.
 *
 * Not supported: the "randomised" blocks of bzip2 before 0.9.5 (refused by name),
 * and more than one stream in a file (the first is decoded, the rest ignored).
 */
internal object Bzip2 {
    class Malformed(message: String) : Exception(message)

    /** The decoded bytes, or [Malformed]. More than [maxOutput] bytes is [Malformed] too. */
    fun decompress(input: ByteArray, maxOutput: Int): ByteArray {
        val bits = Bits(input)
        if (bits.read(8) != 'B'.code || bits.read(8) != 'Z'.code || bits.read(8) != 'h'.code) throw Malformed("not bzip2")
        val level = bits.read(8) - '0'.code
        if (level !in 1..9) throw Malformed("block size $level")
        val blockMax = level * 100_000
        val out = Output(maxOutput)
        val tt = IntArray(blockMax)
        var combined = 0
        while (true) {
            val hi = bits.read(24)
            val lo = bits.read(24)
            val stored = (bits.read(16) shl 16) or bits.read(16)
            if (hi == 0x314159 && lo == 0x265359) {
                val crc = block(bits, tt, blockMax, out)
                if (crc != stored) throw Malformed("block checksum")
                combined = ((combined shl 1) or (combined ushr 31)) xor crc
            } else if (hi == 0x177245 && lo == 0x385090) {
                if (stored != combined) throw Malformed("stream checksum")
                return out.bytes()
            } else {
                throw Malformed("block header")
            }
        }
    }

    private class Bits(private val src: ByteArray) {
        private var pos = 0
        private var buf = 0L
        private var live = 0

        /** The next [n] bits, most significant first; [n] is at most 24. */
        fun read(n: Int): Int {
            while (live < n) {
                if (pos >= src.size) throw Malformed("ends early")
                buf = (buf shl 8) or (src[pos++].toLong() and 0xFF)
                live += 8
            }
            live -= n
            return ((buf ushr live) and ((1L shl n) - 1)).toInt()
        }
    }

    private class Output(private val max: Int) {
        private var buf = ByteArray(minOf(max, 1 shl 16))
        private var n = 0

        fun put(b: Int, times: Int = 1) {
            if (times > max - n) throw Malformed("larger than $max bytes")
            if (n + times > buf.size) buf = buf.copyOf(minOf(max.toLong(), maxOf(buf.size * 2L, n + times.toLong())).toInt())
            if (times == 1) buf[n] = b.toByte() else buf.fill(b.toByte(), n, n + times)
            n += times
        }

        fun bytes(): ByteArray = buf.copyOf(n)
    }

    /** bzip2's CRC-32: the usual polynomial, most significant bit first. */
    private val crcTable = IntArray(256) { i ->
        var c = i shl 24
        repeat(8) { c = if (c < 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        c
    }

    private fun crc(crc: Int, b: Int, times: Int): Int {
        var c = crc
        repeat(times) { c = (c shl 8) xor crcTable[((c ushr 24) xor b) and 0xFF] }
        return c
    }

    /** One block, written to [out]. Returns the checksum of what was written. */
    private fun block(bits: Bits, tt: IntArray, blockMax: Int, out: Output): Int {
        if (bits.read(1) != 0) throw Malformed("randomised blocks are not supported")
        val origPtr = bits.read(24)

        // Which byte values occur: sixteen groups of sixteen.
        val seqToUnseq = IntArray(256)
        var inUse = 0
        val groups = bits.read(16)
        for (g in 0 until 16) {
            if (groups and (0x8000 ushr g) == 0) continue
            val members = bits.read(16)
            for (j in 0 until 16) if (members and (0x8000 ushr j) != 0) seqToUnseq[inUse++] = g * 16 + j
        }
        if (inUse == 0) throw Malformed("no byte values in use")
        val alphaSize = inUse + 2
        val endOfBlock = inUse + 1

        // Which Huffman table each group of 50 symbols uses, move-to-front coded.
        val tables = bits.read(3)
        if (tables !in 2..6) throw Malformed("$tables Huffman tables")
        val selectorCount = bits.read(15)
        if (selectorCount < 1) throw Malformed("no selectors")
        val selectors = IntArray(selectorCount)
        val order = IntArray(tables) { it }
        for (i in 0 until selectorCount) {
            var j = 0
            while (bits.read(1) == 1) if (++j >= tables) throw Malformed("selector")
            val v = order[j]
            System.arraycopy(order, 0, order, 1, j)
            order[0] = v
            selectors[i] = v
        }

        // Each table's code lengths, then the canonical decoding tables from them.
        val limit = Array(tables) { IntArray(MAX_CODE + 2) }
        val base = Array(tables) { IntArray(MAX_CODE + 3) }
        val perm = Array(tables) { IntArray(alphaSize) }
        val minLen = IntArray(tables)
        val maxLen = IntArray(tables)
        val len = IntArray(alphaSize)
        for (t in 0 until tables) {
            var cur = bits.read(5)
            for (i in 0 until alphaSize) {
                while (true) {
                    if (cur !in 1..MAX_CODE) throw Malformed("code length $cur")
                    if (bits.read(1) == 0) break
                    cur += if (bits.read(1) == 0) 1 else -1
                }
                len[i] = cur
            }
            val lo = len.min()
            val hi = len.max()
            var p = 0
            for (l in lo..hi) for (s in 0 until alphaSize) if (len[s] == l) perm[t][p++] = s
            val b = base[t]
            val lim = limit[t]
            for (s in 0 until alphaSize) b[len[s] + 1]++
            for (l in 1 until b.size) b[l] += b[l - 1]
            var vec = 0
            for (l in lo..hi) {
                vec += b[l + 1] - b[l]
                lim[l] = vec - 1
                vec = vec shl 1
            }
            for (l in lo + 1..hi) b[l] = ((lim[l - 1] + 1) shl 1) - b[l]
            minLen[t] = lo
            maxLen[t] = hi
        }

        // The symbols: runs of the front byte (RUNA, RUNB), move-to-front indexes, end of block.
        val mtf = IntArray(256) { it }
        val counts = IntArray(256)
        var n = 0
        var group = -1
        var leftInGroup = 0
        var t = 0
        var run = 0
        var runUnit = 1
        while (true) {
            if (leftInGroup == 0) {
                if (++group >= selectorCount) throw Malformed("more symbols than selectors")
                leftInGroup = 50
                t = selectors[group]
            }
            leftInGroup--
            var codeLen = minLen[t]
            var code = bits.read(codeLen)
            while (code > limit[t][codeLen]) {
                if (++codeLen > maxLen[t]) throw Malformed("no such code")
                code = (code shl 1) or bits.read(1)
            }
            val at = code - base[t][codeLen]
            if (at !in 0 until alphaSize) throw Malformed("no such code")
            val sym = perm[t][at]

            if (sym <= 1) { // RUNA adds the unit, RUNB twice it; the unit doubles each time
                run += runUnit shl sym
                runUnit = runUnit shl 1
                if (run > blockMax) throw Malformed("run longer than the block")
                continue
            }
            if (run > 0) {
                val b = seqToUnseq[mtf[0]]
                if (run > blockMax - n) throw Malformed("block overflows")
                counts[b] += run
                tt.fill(b, n, n + run)
                n += run
                run = 0
                runUnit = 1
            }
            if (sym == endOfBlock) break
            val index = sym - 1
            val v = mtf[index]
            System.arraycopy(mtf, 0, mtf, 1, index)
            mtf[0] = v
            val b = seqToUnseq[v]
            if (n >= blockMax) throw Malformed("block overflows")
            counts[b]++
            tt[n++] = b
        }
        if (origPtr >= n) throw Malformed("start pointer outside the block")

        // Undo the Burrows-Wheeler transform: each entry keeps its byte in the low
        // eight bits and gains, above them, where the next byte of the text is.
        var sum = 0
        val start = IntArray(256)
        for (i in 0 until 256) { start[i] = sum; sum += counts[i] }
        for (i in 0 until n) {
            val b = tt[i] and 0xFF
            val at = start[b]++
            tt[at] = tt[at] or (i shl 8)
        }

        // Undo the first run-length pass: four equal bytes are followed by a count of more.
        var crc = -1
        var pos = tt[origPtr] ushr 8
        var last = -1
        var same = 0
        repeat(n) {
            val entry = tt[pos]
            val b = entry and 0xFF
            pos = entry ushr 8
            if (same == 4) {
                out.put(last, b)
                crc = crc(crc, last, b)
                same = 0
            } else {
                out.put(b)
                crc = crc(crc, b, 1)
                if (b == last) same++ else { last = b; same = 1 }
            }
        }
        return crc.inv()
    }

    private const val MAX_CODE = 20
}
