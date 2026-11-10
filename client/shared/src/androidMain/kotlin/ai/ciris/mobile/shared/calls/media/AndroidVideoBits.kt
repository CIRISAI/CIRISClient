package ai.ciris.mobile.shared.calls.media

import android.graphics.Rect
import android.media.Image
import android.media.MediaCodecInfo
import java.nio.ByteBuffer

/**
 * The byte-level half of Android call video: I420 in and out of MediaCodec's
 * images and buffers, and the payload format of [CallVideoCodec] (Annex B with
 * SPS/PPS before every H.264 keyframe; low-overhead AV1 OBUs with the sequence
 * header before every keyframe). MediaCodec says what it emits; this checks it.
 */
internal object I420 {
    /** Writes [f] into a YUV_420_888 [image] (any pixel and row stride). */
    fun write(f: VideoFrame, image: Image) {
        val planes = image.planes
        writePlane(f.y, f.width, f.height, planes[0].buffer, planes[0].rowStride, planes[0].pixelStride)
        writePlane(f.u, f.width / 2, f.height / 2, planes[1].buffer, planes[1].rowStride, planes[1].pixelStride)
        writePlane(f.v, f.width / 2, f.height / 2, planes[2].buffer, planes[2].rowStride, planes[2].pixelStride)
    }

    private fun writePlane(src: ByteArray, w: Int, h: Int, dst: ByteBuffer, rowStride: Int, pixelStride: Int) {
        if (pixelStride == 1) {
            for (row in 0 until h) {
                dst.position(row * rowStride)
                dst.put(src, row * w, w)
            }
        } else {
            for (row in 0 until h) {
                val base = row * rowStride
                for (col in 0 until w) dst.put(base + col * pixelStride, src[row * w + col])
            }
        }
    }

    /**
     * Writes [f] into a raw codec input buffer whose layout is [colorFormat], for
     * an encoder that gives no input Image. Planar (I420) and semi-planar (NV12).
     */
    fun writeBuffer(f: VideoFrame, dst: ByteBuffer, colorFormat: Int, stride: Int, sliceHeight: Int) {
        val s = if (stride > 0) stride else f.width
        val sh = if (sliceHeight > 0) sliceHeight else f.height
        dst.clear()
        for (row in 0 until f.height) { dst.position(row * s); dst.put(f.y, row * f.width, f.width) }
        val cw = f.width / 2
        val ch = f.height / 2
        val chromaBase = s * sh
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            for (row in 0 until ch) for (col in 0 until cw) {
                val o = chromaBase + row * s + col * 2
                dst.put(o, f.u[row * cw + col]); dst.put(o + 1, f.v[row * cw + col])
            }
            dst.position(chromaBase + ch * s)
        } else {
            val cs = s / 2
            for (row in 0 until ch) { dst.position(chromaBase + row * cs); dst.put(f.u, row * cw, cw) }
            val vBase = chromaBase + cs * (sh / 2)
            for (row in 0 until ch) { dst.position(vBase + row * cs); dst.put(f.v, row * cw, cw) }
        }
    }

    /** Reads a YUV_420_888 [image] (cropped to its crop rect, rounded down to even) as I420. */
    fun read(image: Image, timestampUs: Long): VideoFrame? {
        val crop = image.cropRect ?: Rect(0, 0, image.width, image.height)
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        if (w <= 0 || h <= 0) return null
        val planes = image.planes
        val y = readPlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, crop.left, crop.top, w, h)
        val u = readPlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, crop.left / 2, crop.top / 2, w / 2, h / 2)
        val v = readPlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, crop.left / 2, crop.top / 2, w / 2, h / 2)
        return VideoFrame(w, h, y, u, v, timestampUs)
    }

    private fun readPlane(src: ByteBuffer, rowStride: Int, pixelStride: Int, x0: Int, y0: Int, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        if (pixelStride == 1) {
            for (row in 0 until h) {
                src.position((y0 + row) * rowStride + x0)
                src.get(out, row * w, w)
            }
        } else {
            for (row in 0 until h) {
                val base = (y0 + row) * rowStride + x0 * pixelStride
                for (col in 0 until w) out[row * w + col] = src.get(base + col * pixelStride)
            }
        }
        return out
    }

    /**
     * Reads a raw decoder output buffer of [colorFormat] (planar or semi-planar)
     * as I420, for a decoder that gives no output Image.
     */
    fun readBuffer(src: ByteBuffer, colorFormat: Int, width: Int, height: Int, stride: Int, sliceHeight: Int, crop: Rect, timestampUs: Long): VideoFrame? {
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        if (w <= 0 || h <= 0) return null
        val s = if (stride > 0) stride else width
        val sh = if (sliceHeight > 0) sliceHeight else height
        val base = src.position()
        val y = ByteArray(w * h)
        for (row in 0 until h) { src.position(base + (crop.top + row) * s + crop.left); src.get(y, row * w, w) }
        val cw = w / 2
        val ch = h / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        val chroma = base + s * sh
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
        ) {
            for (row in 0 until ch) for (col in 0 until cw) {
                val o = chroma + (crop.top / 2 + row) * s + (crop.left / 2 + col) * 2
                u[row * cw + col] = src.get(o); v[row * cw + col] = src.get(o + 1)
            }
        } else {
            val cs = s / 2
            val vBase = chroma + cs * (sh / 2)
            for (row in 0 until ch) {
                src.position(chroma + (crop.top / 2 + row) * cs + crop.left / 2); src.get(u, row * cw, cw)
                src.position(vBase + (crop.top / 2 + row) * cs + crop.left / 2); src.get(v, row * cw, cw)
            }
        }
        return VideoFrame(w, h, y, u, v, timestampUs)
    }

    /** Rotates an I420 frame clockwise by 0, 90, 180 or 270 degrees. */
    fun rotate(f: VideoFrame, degrees: Int): VideoFrame {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return f
        val (nw, nh) = if (d == 180) f.width to f.height else f.height to f.width
        return VideoFrame(
            nw, nh,
            rotatePlane(f.y, f.width, f.height, d),
            rotatePlane(f.u, f.width / 2, f.height / 2, d),
            rotatePlane(f.v, f.width / 2, f.height / 2, d),
            f.timestampUs,
        )
    }

    private fun rotatePlane(src: ByteArray, w: Int, h: Int, d: Int): ByteArray {
        val out = ByteArray(w * h)
        when (d) {
            90 -> for (r in 0 until h) for (c in 0 until w) out[c * h + (h - 1 - r)] = src[r * w + c]
            180 -> for (i in src.indices) out[src.size - 1 - i] = src[i]
            270 -> for (r in 0 until h) for (c in 0 until w) out[(w - 1 - c) * h + r] = src[r * w + c]
        }
        return out
    }

    /**
     * RGBA_8888 (an ImageReader on a VirtualDisplay) to I420, BT.601 limited range,
     * matching [VideoFrame.toArgb]. Chroma is taken from the top-left pixel of each 2x2 block.
     */
    fun fromRgba(src: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int, timestampUs: Long): VideoFrame {
        val w = width and 1.inv()
        val h = height and 1.inv()
        val y = ByteArray(w * h)
        val u = ByteArray(w * h / 4)
        val v = ByteArray(w * h / 4)
        val row = ByteArray(rowStride)
        for (r in 0 until h) {
            src.position(r * rowStride)
            src.get(row, 0, minOf(rowStride, src.remaining()))
            for (c in 0 until w) {
                val o = c * pixelStride
                val R = row[o].toInt() and 0xFF
                val G = row[o + 1].toInt() and 0xFF
                val B = row[o + 2].toInt() and 0xFF
                y[r * w + c] = (((66 * R + 129 * G + 25 * B + 128) shr 8) + 16).toByte()
                if (r and 1 == 0 && c and 1 == 0) {
                    val ci = (r / 2) * (w / 2) + c / 2
                    u[ci] = (((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128).toByte()
                    v[ci] = (((112 * R - 94 * G - 18 * B + 128) shr 8) + 128).toByte()
                }
            }
        }
        return VideoFrame(w, h, y, u, v, timestampUs)
    }
}

/** H.264 Annex B: split, join, and what a payload carries. */
internal object AndroidAnnexB {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    private val START = byteArrayOf(0, 0, 0, 1)

    fun startsWithStartCode(b: ByteArray): Boolean =
        (b.size >= 3 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte()) ||
            (b.size >= 4 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && b[3] == 1.toByte())

    /** The NAL units of one access unit, start codes removed. */
    fun split(au: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < au.size) {
            if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 1.toByte()) {
                starts += i + 3
                i += 3
            } else i++
        }
        return starts.mapIndexed { k, s ->
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else au.size
            while (end > s && k + 1 < starts.size && au[end - 1] == 0.toByte()) end--
            au.copyOfRange(s, end)
        }.filter { it.isNotEmpty() }
    }

    /** Length-prefixed (4-byte, AVCC) to Annex B, for an encoder that ignores Annex B. */
    fun fromLengthPrefixed(data: ByteArray): ByteArray? {
        val nals = mutableListOf<ByteArray>()
        var i = 0
        while (i + 4 <= data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            i += 4
            if (n <= 0 || i + n > data.size) return null
            nals += data.copyOfRange(i, i + n)
            i += n
        }
        return if (i == data.size && nals.isNotEmpty()) join(nals) else null
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

    /**
     * The temporal layer of an access unit, from the bytes: a non-reference
     * picture (nal_ref_idc 0) is droppable, which is what the upper layer of a
     * two-layer temporal pattern is. A reference picture is the base layer.
     */
    fun temporalLayer(nals: List<ByteArray>): Int {
        val slice = nals.firstOrNull { type(it) == 1 || type(it) == NAL_IDR } ?: return 0
        return if ((slice[0].toInt() shr 5) and 0x03 == 0) 1 else 0
    }

    /** One access unit in the payload format: Annex B, SPS/PPS in front of a keyframe. */
    fun payload(raw: ByteArray, keyframe: Boolean, paramSets: List<ByteArray>): ByteArray {
        val annexB = if (startsWithStartCode(raw)) raw else fromLengthPrefixed(raw) ?: raw
        val nals = split(annexB)
        val hasIdr = nals.any { type(it) == NAL_IDR }
        if (!(keyframe || hasIdr)) return annexB
        val hasSps = nals.any { type(it) == NAL_SPS }
        val hasPps = nals.any { type(it) == NAL_PPS }
        if (hasSps && hasPps) return annexB
        val missing = paramSets.filter { (type(it) == NAL_SPS && !hasSps) || (type(it) == NAL_PPS && !hasPps) }
        return join(missing + nals)
    }
}

/** AV1 low-overhead OBUs: normalise sizes, and keep the sequence header before keyframes. */
internal object AndroidAv1 {
    const val OBU_SEQUENCE_HEADER = 1
    const val OBU_TEMPORAL_DELIMITER = 2

    class Obu(val header: ByteArray, val payload: ByteArray) {
        val type: Int get() = (header[0].toInt() shr 3) and 0x0F
        val temporalId: Int get() = if (header.size > 1) (header[1].toInt() and 0xFF) shr 5 else 0
    }

    /** Parses OBUs; one without a size field runs to the end of the data. Null if malformed. */
    fun parse(data: ByteArray): List<Obu>? {
        val out = mutableListOf<Obu>()
        var i = 0
        while (i < data.size) {
            val h0 = data[i].toInt() and 0xFF
            if (h0 and 0x80 != 0) return null // forbidden bit
            val ext = h0 and 0x04 != 0
            val hasSize = h0 and 0x02 != 0
            val hLen = if (ext) 2 else 1
            if (i + hLen > data.size) return null
            val header = data.copyOfRange(i, i + hLen)
            header[0] = (header[0].toInt() and 0x02.inv()).toByte() // size flag re-added on write
            i += hLen
            val size: Int
            if (hasSize) {
                var v = 0L
                var shift = 0
                while (true) {
                    if (i >= data.size || shift > 56) return null
                    val b = data[i++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (v > data.size - i) return null
                size = v.toInt()
            } else size = data.size - i
            out += Obu(header, data.copyOfRange(i, i + size))
            i += size
        }
        return out
    }

    fun write(obus: List<Obu>): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        for (o in obus) {
            val h = o.header.copyOf()
            h[0] = (h[0].toInt() or 0x02).toByte()
            bytes.write(h)
            var n = o.payload.size.toLong()
            do {
                var b = (n and 0x7F).toInt()
                n = n shr 7
                if (n != 0L) b = b or 0x80
                bytes.write(b)
            } while (n != 0L)
            bytes.write(o.payload)
        }
        return bytes.toByteArray()
    }

    /** The OBUs of a codec-config buffer: an av1C record (marker 0x81) or bare OBUs. */
    fun configObus(config: ByteArray): List<Obu> {
        val obus = if (config.size >= 4 && config[0] == 0x81.toByte()) config.copyOfRange(4, config.size) else config
        return parse(obus).orEmpty()
    }

    /** One temporal unit in the payload format, and its temporal layer. */
    fun payload(raw: ByteArray, keyframe: Boolean, sequenceHeader: Obu?): Pair<ByteArray, Int> {
        val obus = parse(raw) ?: return raw to 0
        val temporal = obus.firstOrNull { it.header.size > 1 && it.type != OBU_TEMPORAL_DELIMITER }?.temporalId ?: 0
        if (!keyframe || obus.any { it.type == OBU_SEQUENCE_HEADER } || sequenceHeader == null) return write(obus) to temporal
        val at = if (obus.firstOrNull()?.type == OBU_TEMPORAL_DELIMITER) 1 else 0
        return write(obus.take(at) + sequenceHeader + obus.drop(at)) to temporal
    }
}
