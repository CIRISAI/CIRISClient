package ai.ciris.mobile.shared.calls.media

/**
 * The byte-level shapes the iOS codecs translate between, pure Kotlin so nothing
 * here touches a framework. VideoToolbox speaks length-prefixed NAL units (AVCC)
 * and wants its own `av1C` record; the wire (CallVideoCodec) speaks Annex B and
 * low-overhead OBUs.
 */
internal object H264Bitstream {
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /** Length-prefixed NAL units ([lengthSize] bytes, big-endian) to a list of NAL units. */
    fun splitAvcc(data: ByteArray, lengthSize: Int = 4): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + lengthSize <= data.size) {
            var len = 0
            for (k in 0 until lengthSize) len = (len shl 8) or (data[i + k].toInt() and 0xFF)
            i += lengthSize
            if (len <= 0 || i + len > data.size) break
            out += data.copyOfRange(i, i + len)
            i += len
        }
        return out
    }

    /** An Annex B access unit (3- or 4-byte start codes) to its NAL units. */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>() // index of the first byte AFTER each start code
        val codeAt = ArrayList<Int>() // index of the start code's first zero
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val begin = if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i
                codeAt += begin
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        val out = ArrayList<ByteArray>()
        for (k in starts.indices) {
            val end = if (k + 1 < starts.size) codeAt[k + 1] else data.size
            if (end > starts[k]) out += data.copyOfRange(starts[k], end)
        }
        return out
    }

    /** NAL units to one Annex B access unit. */
    fun toAnnexB(nals: List<ByteArray>): ByteArray {
        val out = ByteArray(nals.sumOf { it.size + START_CODE.size })
        var o = 0
        for (n in nals) {
            START_CODE.copyInto(out, o); o += START_CODE.size
            n.copyInto(out, o); o += n.size
        }
        return out
    }

    /** NAL units to one AVCC sample with 4-byte lengths, the form VideoToolbox decodes. */
    fun toAvcc(nals: List<ByteArray>): ByteArray {
        val out = ByteArray(nals.sumOf { it.size + 4 })
        var o = 0
        for (n in nals) {
            val len = n.size
            out[o] = (len ushr 24).toByte(); out[o + 1] = (len ushr 16).toByte()
            out[o + 2] = (len ushr 8).toByte(); out[o + 3] = len.toByte()
            o += 4
            n.copyInto(out, o); o += n.size
        }
        return out
    }
}

/** AV1 low-overhead OBUs (`obu_has_size_field = 1`), and the `av1C` record built from a sequence header. */
internal object Av1Bitstream {
    const val OBU_SEQUENCE_HEADER = 1
    const val OBU_TEMPORAL_DELIMITER = 2

    class Obu(val type: Int, /** The whole OBU, header and size field included. */ val bytes: ByteArray, val payloadOffset: Int)

    /** Splits low-overhead OBUs; stops at the first one without a size field (it would run to the end). */
    fun split(data: ByteArray): List<Obu> {
        val out = ArrayList<Obu>()
        var i = 0
        while (i < data.size) {
            val start = i
            val header = data[i].toInt() and 0xFF
            val type = (header shr 3) and 0x0F
            val ext = (header shr 2) and 1
            val hasSize = (header shr 1) and 1
            i += 1 + ext
            if (i > data.size) break
            val size: Int
            if (hasSize == 1) {
                var value = 0L
                var shift = 0
                var n = 0
                while (true) {
                    if (i >= data.size || n >= 8) return out
                    val b = data[i].toInt() and 0xFF
                    value = value or ((b and 0x7F).toLong() shl shift)
                    i++; n++
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (value > Int.MAX_VALUE) return out
                size = value.toInt()
            } else {
                size = data.size - i
            }
            val payload = i
            if (payload + size > data.size) break
            i = payload + size
            out += Obu(type, data.copyOfRange(start, i), payload - start)
        }
        return out
    }

    class SequenceHeader(
        val profile: Int,
        val level0: Int,
        val tier0: Int,
        val highBitdepth: Boolean,
        val twelveBit: Boolean,
        val monochrome: Boolean,
        val subsamplingX: Int,
        val subsamplingY: Int,
        val chromaSamplePosition: Int,
        val maxWidth: Int,
        val maxHeight: Int,
    )

    private class Bits(private val b: ByteArray, private val from: Int) {
        private var pos = from * 8
        fun f(n: Int): Long {
            var v = 0L
            repeat(n) {
                val byte = pos / 8
                require(byte < b.size) { "AV1 sequence header ran out" }
                v = (v shl 1) or ((b[byte].toInt() shr (7 - pos % 8)) and 1).toLong()
                pos++
            }
            return v
        }
        fun flag() = f(1) == 1L
        fun uvlc(): Long {
            var zeros = 0
            while (!flag()) { zeros++; require(zeros < 32) { "uvlc" } }
            return if (zeros == 0) 0 else f(zeros) + (1L shl zeros) - 1
        }
    }

    /** Parses a sequence header OBU's payload (AV1 spec 5.5) as far as color_config. Null if it does not parse. */
    fun parseSequenceHeader(obu: Obu): SequenceHeader? = runCatching {
        val r = Bits(obu.bytes, obu.payloadOffset)
        val profile = r.f(3).toInt()
        r.f(1) // still_picture
        val reduced = r.flag()
        var level0: Int
        var tier0 = 0
        if (reduced) {
            level0 = r.f(5).toInt()
        } else {
            var decoderModelInfo = false
            var bufferDelayLength = 0
            if (r.flag()) { // timing_info_present_flag
                r.f(32); r.f(32)
                if (r.flag()) r.uvlc() // equal_picture_interval → num_ticks_per_picture_minus_1
                decoderModelInfo = r.flag()
                if (decoderModelInfo) {
                    bufferDelayLength = r.f(5).toInt() + 1
                    r.f(32); r.f(5); r.f(5)
                }
            }
            val initialDisplayDelay = r.flag()
            val ops = r.f(5).toInt() + 1
            level0 = 0
            for (op in 0 until ops) {
                r.f(12) // operating_point_idc
                val level = r.f(5).toInt()
                val tier = if (level > 7) r.f(1).toInt() else 0
                if (op == 0) { level0 = level; tier0 = tier }
                if (decoderModelInfo && r.flag()) { r.f(bufferDelayLength); r.f(bufferDelayLength); r.f(1) }
                if (initialDisplayDelay && r.flag()) r.f(4)
            }
        }
        val wBits = r.f(4).toInt() + 1
        val hBits = r.f(4).toInt() + 1
        val maxW = r.f(wBits).toInt() + 1
        val maxH = r.f(hBits).toInt() + 1
        val frameIds = if (reduced) false else r.flag()
        if (frameIds) { r.f(4); r.f(3) }
        r.f(1); r.f(1); r.f(1) // use_128x128_superblock, enable_filter_intra, enable_intra_edge_filter
        if (!reduced) {
            r.f(1); r.f(1); r.f(1); r.f(1) // interintra, masked, warped, dual_filter
            val orderHint = r.flag()
            if (orderHint) { r.f(1); r.f(1) } // jnt_comp, ref_frame_mvs
            val forceScreenContent = if (r.flag()) 2 else r.f(1).toInt()
            if (forceScreenContent > 0) { if (!r.flag()) r.f(1) }
            if (orderHint) r.f(3)
        }
        r.f(1); r.f(1); r.f(1) // superres, cdef, restoration
        // color_config
        val high = r.flag()
        val twelve = if (profile == 2 && high) r.flag() else false
        val bitDepth = if (twelve) 12 else if (high) 10 else 8
        val mono = if (profile == 1) false else r.flag()
        var cp = 2; var tc = 2; var mc = 2
        if (r.flag()) { cp = r.f(8).toInt(); tc = r.f(8).toInt(); mc = r.f(8).toInt() }
        var ssx = 1; var ssy = 1; var csp = 0
        if (mono) {
            r.f(1)
        } else if (cp == 1 && tc == 13 && mc == 0) {
            ssx = 0; ssy = 0
        } else {
            r.f(1) // color_range
            when (profile) {
                0 -> { ssx = 1; ssy = 1 }
                1 -> { ssx = 0; ssy = 0 }
                else -> if (bitDepth == 12) { ssx = r.f(1).toInt(); ssy = if (ssx == 1) r.f(1).toInt() else 0 } else { ssx = 1; ssy = 0 }
            }
            if (ssx == 1 && ssy == 1) csp = r.f(2).toInt()
        }
        SequenceHeader(profile, level0, tier0, high, twelve, mono, ssx, ssy, csp, maxW, maxH)
    }.getOrNull()

    /** The ISOBMFF `av1C` record (AV1-ISOBMFF 2.3): four bytes, then the sequence header OBU. */
    fun av1C(seq: SequenceHeader, sequenceHeaderObu: ByteArray): ByteArray {
        val head = byteArrayOf(
            0x81.toByte(), // marker 1, version 1
            ((seq.profile shl 5) or (seq.level0 and 0x1F)).toByte(),
            ((seq.tier0 shl 7) or (if (seq.highBitdepth) 0x40 else 0) or (if (seq.twelveBit) 0x20 else 0) or
                (if (seq.monochrome) 0x10 else 0) or (seq.subsamplingX shl 3) or (seq.subsamplingY shl 2) or
                (seq.chromaSamplePosition and 3)).toByte(),
            0, // no initial_presentation_delay
        )
        return head + sequenceHeaderObu
    }
}
