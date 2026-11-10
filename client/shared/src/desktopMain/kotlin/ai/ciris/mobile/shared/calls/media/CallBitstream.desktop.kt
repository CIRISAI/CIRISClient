package ai.ciris.mobile.shared.calls.media

/**
 * The byte-level half of [CallVideoCodec]'s payload format, in pure Kotlin.
 * A byte-for-byte copy of CallBitstream.wasmJs.kt (FFmpeg on desktop, like
 * WebCodecs on the web, is checked rather than trusted). It is pure Kotlin and
 * belongs in commonMain; it is copied, not hoisted, only so the per-platform call
 * media branches do not collide. Hoist both copies to one when they merge.
 *
 * - H.264: Annex B with SPS and PPS before every keyframe. Chrome's `annexb`
 *   output carries them inline on keyframes; when an encoder does not, the last
 *   ones seen (or the avcC `description`) are prepended. If an encoder ignores
 *   `annexb` and emits length-prefixed NAL units, they are rewritten.
 * - AV1: low-overhead OBUs, every one with `obu_has_size_field = 1`, and the
 *   sequence header OBU before every keyframe (from the stream, or from the av1C
 *   `description`).
 */
internal object AnnexB {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    private val START = byteArrayOf(0, 0, 0, 1)

    fun startsWithStartCode(b: ByteArray): Boolean =
        (b.size >= 3 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte()) ||
            (b.size >= 4 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && b[3] == 1.toByte())

    /** The NAL units of one access unit, start codes removed. */
    fun split(au: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>() // index of first byte after each start code
        var i = 0
        while (i + 2 < au.size) {
            if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 1.toByte()) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { k, s ->
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else au.size
            // A four-byte start code's leading zero, and trailing_zero_8bits, belong to no NAL.
            while (end > s && k + 1 < starts.size && au[end - 1] == 0.toByte()) end--
            au.copyOfRange(s, end)
        }.filter { it.isNotEmpty() }
    }

    fun type(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    fun join(nals: List<ByteArray>): ByteArray {
        val out = ByteArray(nals.sumOf { it.size + START.size })
        var o = 0
        for (n in nals) {
            START.copyInto(out, o); o += START.size
            n.copyInto(out, o); o += n.size
        }
        return out
    }

    fun isKeyframe(au: ByteArray): Boolean = split(au).any { type(it) == NAL_IDR }

    fun lengthPrefixedToAnnexB(data: ByteArray, lengthSize: Int): ByteArray {
        val nals = mutableListOf<ByteArray>()
        var i = 0
        while (i + lengthSize <= data.size) {
            var len = 0
            repeat(lengthSize) { len = (len shl 8) or (data[i + it].toInt() and 0xFF) }
            i += lengthSize
            if (len <= 0 || i + len > data.size) break
            nals += data.copyOfRange(i, i + len)
            i += len
        }
        return join(nals)
    }

    /** An avcC record: its NAL length size and its SPS and PPS, or null if it is not one. */
    class AvcC(val lengthSize: Int, val sps: List<ByteArray>, val pps: List<ByteArray>)

    fun parseAvcC(d: ByteArray): AvcC? {
        if (d.size < 7 || d[0] != 1.toByte()) return null
        val lengthSize = (d[4].toInt() and 0x03) + 1
        var i = 5
        fun sets(count: Int): List<ByteArray>? {
            val out = mutableListOf<ByteArray>()
            repeat(count) {
                if (i + 2 > d.size) return null
                val len = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
                i += 2
                if (i + len > d.size) return null
                out += d.copyOfRange(i, i + len)
                i += len
            }
            return out
        }
        val sps = sets(d[i++].toInt() and 0x1F) ?: return null
        if (i >= d.size) return null
        val pps = sets(d[i++].toInt() and 0xFF) ?: return null
        return AvcC(lengthSize, sps, pps)
    }
}

/** Puts every outgoing H.264 access unit in the agreed form. One per encoder. */
internal class H264Packetizer {
    private var sps: List<ByteArray> = emptyList()
    private var pps: List<ByteArray> = emptyList()
    private var lengthSize = 0

    fun onDescription(description: ByteArray) {
        val c = AnnexB.parseAvcC(description) ?: return
        lengthSize = c.lengthSize
        if (c.sps.isNotEmpty()) sps = c.sps
        if (c.pps.isNotEmpty()) pps = c.pps
    }

    fun accessUnit(data: ByteArray, keyframe: Boolean): ByteArray {
        val au = if (!AnnexB.startsWithStartCode(data) && lengthSize > 0) AnnexB.lengthPrefixedToAnnexB(data, lengthSize) else data
        val nals = AnnexB.split(au)
        val s = nals.filter { AnnexB.type(it) == AnnexB.NAL_SPS }
        val p = nals.filter { AnnexB.type(it) == AnnexB.NAL_PPS }
        if (s.isNotEmpty()) sps = s
        if (p.isNotEmpty()) pps = p
        if (!keyframe || (s.isNotEmpty() && p.isNotEmpty()) || sps.isEmpty() || pps.isEmpty()) return au
        val rest = nals.filter { val t = AnnexB.type(it); t != AnnexB.NAL_SPS && t != AnnexB.NAL_PPS }
        return AnnexB.join(sps + pps + rest)
    }
}

internal object Obu {
    const val SEQUENCE_HEADER = 1
    const val TEMPORAL_DELIMITER = 2

    /** One OBU: its header bytes (1, or 2 with the extension) and its payload. */
    class Unit(val header: ByteArray, val payload: ByteArray) {
        val type: Int get() = (header[0].toInt() shr 3) and 0x0F

        /** The low-overhead form: the size field set, then leb128(payload size). */
        fun encode(): ByteArray {
            val h = header.copyOf()
            h[0] = (h[0].toInt() or 0x02).toByte()
            val size = leb128(payload.size)
            return h + size + payload
        }
    }

    fun leb128(value: Int): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out += b.toByte()
        } while (v != 0)
        return out.toByteArray()
    }

    /** Parses OBUs with or without size fields; null if the bytes are not OBUs. */
    fun parse(data: ByteArray): List<Unit>? {
        val out = mutableListOf<Unit>()
        var i = 0
        while (i < data.size) {
            val h0 = data[i].toInt() and 0xFF
            if (h0 and 0x80 != 0) return null // forbidden bit
            val ext = h0 and 0x04 != 0
            val hasSize = h0 and 0x02 != 0
            val hlen = if (ext) 2 else 1
            if (i + hlen > data.size) return null
            val header = data.copyOfRange(i, i + hlen)
            i += hlen
            val len: Int
            if (hasSize) {
                var v = 0L
                var shift = 0
                while (true) {
                    if (i >= data.size || shift > 56) return null
                    val b = data[i++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    shift += 7
                    if (b and 0x80 == 0) break
                }
                if (v > data.size - i) return null
                len = v.toInt()
            } else {
                len = data.size - i
            }
            out += Unit(header, data.copyOfRange(i, i + len))
            i += len
        }
        return out
    }

    fun join(units: List<Unit>): ByteArray {
        val parts = units.map { it.encode() }
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    /** Per the payload format a keyframe carries the sequence header, so that is what a receiver keys on. */
    fun isKeyframe(data: ByteArray): Boolean = parse(data)?.any { it.type == SEQUENCE_HEADER } == true

    /** The av1C record's configOBUs (after its four fixed bytes), which hold the sequence header. */
    fun sequenceHeaderFromAv1C(d: ByteArray): Unit? {
        if (d.size <= 4 || (d[0].toInt() and 0x80) == 0) return null
        return parse(d.copyOfRange(4, d.size))?.firstOrNull { it.type == SEQUENCE_HEADER }
    }
}

/** Puts every outgoing AV1 temporal unit in the agreed form. One per encoder. */
internal class Av1Packetizer {
    private var sequenceHeader: Obu.Unit? = null

    fun onDescription(description: ByteArray) {
        Obu.sequenceHeaderFromAv1C(description)?.let { sequenceHeader = it }
    }

    fun temporalUnit(data: ByteArray, keyframe: Boolean): ByteArray {
        val units = Obu.parse(data) ?: return data
        val seq = units.firstOrNull { it.type == Obu.SEQUENCE_HEADER }
        if (seq != null) sequenceHeader = seq
        val cached = sequenceHeader
        if (keyframe && seq == null && cached != null) {
            // After a temporal delimiter if there is one, as the spec orders them.
            val td = units.takeWhile { it.type == Obu.TEMPORAL_DELIMITER }
            return Obu.join(td + cached + units.drop(td.size))
        }
        return Obu.join(units)
    }
}
