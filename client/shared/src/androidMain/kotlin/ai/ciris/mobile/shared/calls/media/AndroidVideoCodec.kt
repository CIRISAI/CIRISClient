package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Android call video codecs (CSD-134) on MediaCodec, in buffer mode (no Surface),
 * so frames in and out are the I420 [VideoFrame] every platform shares.
 *
 * Encoder choice, per the codec rule: AV1 (`0x01`) only where a HARDWARE AV1
 * encoder says it runs this size at this rate; otherwise H.264 (`0x04`),
 * constrained baseline. Decoding: H.264 always; AV1 only on a hardware decoder.
 * There is no software AV1 decoder in this build (dav1d is not bundled), so a
 * device without one shows an AV1 publisher's tile as audio only.
 *
 * Both are asynchronous (MediaCodec.Callback on their own HandlerThread) and
 * deliver in codec output order, which is submission order for these streams
 * (no B-frames: baseline H.264, and AV1 real-time encoders do not reorder).
 */
private const val TAG = "CallVideo"
private const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
private const val MIME_AV1 = "video/av01"
/** Frames waiting for an input buffer before the oldest is dropped: a live call wants the newest. */
private const val MAX_PENDING = 3

private fun isHardware(info: MediaCodecInfo): Boolean =
    if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly
    else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.") && !info.name.contains("sw", ignoreCase = true)

private fun codecsFor(mime: String, encoder: Boolean): List<MediaCodecInfo> =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
        info.isEncoder == encoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }

/** A hardware AV1 encoder that says it does [width]x[height] at [fps], or null. */
internal fun realtimeAv1Encoder(width: Int, height: Int, fps: Int): MediaCodecInfo? =
    codecsFor(MIME_AV1, encoder = true).firstOrNull { info ->
        isHardware(info) && runCatching {
            val caps = info.getCapabilitiesForType(MIME_AV1)
            caps.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble()) &&
                caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        }.getOrDefault(false)
    }

/** The H.264 encoder: a hardware one first, any one that takes flexible YUV otherwise. */
internal fun avcEncoder(): MediaCodecInfo? {
    val all = codecsFor(MIME_AVC, encoder = true).filter { info ->
        runCatching { info.getCapabilitiesForType(MIME_AVC).colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) }
            .getOrDefault(false)
    }
    return all.firstOrNull { isHardware(it) } ?: all.firstOrNull()
}

/**
 * The encoder. Configured lazily on the first frame, and again whenever the
 * frame size changes (a camera turned, a share started), because MediaCodec
 * cannot change size in place; the first picture after that is a keyframe.
 */
internal class AndroidVideoEncoder(
    private val info: MediaCodecInfo,
    private val mime: String,
    private val fps: Int,
    private val bitrate: Int,
) : VideoEncoder {
    override val codecId: Int = if (mime == MIME_AV1) CallVideoCodec.AV1_SVC else CallVideoCodec.TEMPORAL_LAYERED
    override val codecName: String = if (mime == MIME_AV1) "av1" else "h264"

    private class Pending(val frame: VideoFrame, val forceKeyframe: Boolean, val onChunk: (EncodedChunk) -> Unit)

    private val lock = Any()
    private val thread = HandlerThread("ciris-call-venc").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var inputColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private var inputStride = 0
    private var inputSliceHeight = 0
    private val pending = ArrayDeque<Pending>()
    private val freeInputs = ArrayDeque<Int>()
    /** Output callbacks by presentation time, so each frame's chunks go to the call that sent it. */
    private val callbacks = HashMap<Long, (EncodedChunk) -> Unit>()
    private var lastCallback: ((EncodedChunk) -> Unit)? = null
    private var lastPtsUs = Long.MIN_VALUE
    /** H.264 SPS and PPS (no start codes), from the codec-config output. */
    private var paramSets: List<ByteArray> = emptyList()
    private var av1SequenceHeader: AndroidAv1.Obu? = null
    private var closed = false
    private var refusedSize: Pair<Int, Int>? = null

    /**
     * True when `android.generic.2` was REQUESTED and configure accepted it. Not a
     * promise: c2.android.avc.encoder accepts and ignores it ("no c2 equivalents
     * for ts-schema"). So each chunk's layer is read from its own bytes (H.264
     * nal_ref_idc, AV1 OBU extension temporal_id): a single-layer stream reads as
     * layer 0 throughout, which is the truth.
     */
    @Volatile var temporalLayeringRequested = false
        private set

    override fun encode(frame: VideoFrame, forceKeyframe: Boolean, onChunk: (EncodedChunk) -> Unit) {
        synchronized(lock) {
            if (closed) return
            if (codec == null || frame.width != width || frame.height != height) {
                // A size the codec refused stays refused: do not retry it on every frame.
                if (refusedSize == frame.width to frame.height) return
                if (!configure(frame.width, frame.height)) { refusedSize = frame.width to frame.height; return }
            }
            pending.addLast(Pending(frame, forceKeyframe, onChunk))
            while (pending.size > MAX_PENDING) {
                val dropped = pending.removeFirst()
                // A dropped keyframe request still has to happen.
                if (dropped.forceKeyframe) pending.addFirst(Pending(pending.removeFirst().frame, true, onChunk))
            }
            feed()
        }
    }

    private fun format(w: Int, h: Int, layered: Boolean): MediaFormat = MediaFormat.createVideoFormat(mime, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        // CallVideo forces one at least every 2 s; this is the codec's own ceiling.
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        val caps = info.getCapabilitiesForType(mime)
        if (caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        if (mime == MIME_AVC) {
            val profiles = caps.profileLevels.map { it.profile }.toSet()
            val cbp = if (Build.VERSION.SDK_INT >= 27) MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline else -1
            val profile = if (cbp in profiles) cbp else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            setInteger(MediaFormat.KEY_PROFILE, profile)
            // KEY_LEVEL is required alongside KEY_PROFILE by some encoders.
            val level = caps.profileLevels.filter { it.profile == profile }.maxOfOrNull { it.level }
            if (level != null) setInteger(MediaFormat.KEY_LEVEL, level)
        }
        // Not KEY_PREPEND_HEADER_TO_SYNC_FRAMES: c2.android.avc.encoder fails configure
        // on it (BAD_VALUE, API 36 emulator), and [chunk] prepends SPS/PPS itself.
        if (layered && Build.VERSION.SDK_INT >= 25) setString(MediaFormat.KEY_TEMPORAL_LAYERING, "android.generic.2")
    }

    private fun configure(w: Int, h: Int): Boolean {
        release()
        for (layered in listOf(true, false)) {
            val c = runCatching { MediaCodec.createByCodecName(info.name) }.getOrElse {
                Log.e(TAG, "encoder ${info.name} could not be created", it); return false
            }
            try {
                c.setCallback(callback(c), handler)
                c.configure(format(w, h, layered), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inFmt = c.inputFormat
                inputColorFormat = inFmt.intOr(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                inputStride = inFmt.intOr(MediaFormat.KEY_STRIDE, w)
                inputSliceHeight = inFmt.intOr(MediaFormat.KEY_SLICE_HEIGHT, h)
                c.start()
                codec = c
                width = w
                height = h
                temporalLayeringRequested = layered && Build.VERSION.SDK_INT >= 25
                return true
            } catch (e: Exception) {
                Log.w(TAG, "encoder ${info.name} refused ${w}x$h (temporal layers: $layered)", e)
                c.release()
            }
        }
        return false
    }

    private fun callback(c: MediaCodec) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(lock) {
                if (this@AndroidVideoEncoder.codec !== c) return
                freeInputs.addLast(index)
                feed()
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val emit: Pair<EncodedChunk, (EncodedChunk) -> Unit>?
            synchronized(lock) {
                if (this@AndroidVideoEncoder.codec !== c) return
                val buf = c.getOutputBuffer(index)
                val raw = ByteArray(info.size)
                if (buf != null) { buf.position(info.offset); buf.get(raw) }
                c.releaseOutputBuffer(index, false)
                emit = if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    takeConfig(raw); null
                } else if (raw.isEmpty()) null else chunk(raw, info)
            }
            emit?.let { (chunk, cb) -> cb(chunk) }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(lock) {
                if (mime == MIME_AVC) {
                    val sets = listOf("csd-0", "csd-1").mapNotNull { k -> format.getByteBuffer(k)?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } } }
                    if (sets.isNotEmpty()) takeConfig(sets.fold(ByteArray(0)) { a, b -> a + b })
                } else format.getByteBuffer("csd-0")?.let { b -> takeConfig(ByteArray(b.remaining()).also { b.duplicate().get(it) }) }
            }
        }
    }

    private fun takeConfig(raw: ByteArray) {
        if (mime == MIME_AVC) {
            val nals = AndroidAnnexB.split(if (AndroidAnnexB.startsWithStartCode(raw)) raw else AndroidAnnexB.fromLengthPrefixed(raw) ?: raw)
            val sets = nals.filter { AndroidAnnexB.type(it) == AndroidAnnexB.NAL_SPS || AndroidAnnexB.type(it) == AndroidAnnexB.NAL_PPS }
            if (sets.any { AndroidAnnexB.type(it) == AndroidAnnexB.NAL_SPS }) paramSets = sets
        } else {
            AndroidAv1.configObus(raw).firstOrNull { it.type == AndroidAv1.OBU_SEQUENCE_HEADER }?.let { av1SequenceHeader = it }
        }
    }

    private fun chunk(raw: ByteArray, info: MediaCodec.BufferInfo): Pair<EncodedChunk, (EncodedChunk) -> Unit>? {
        val cb = callbacks.remove(info.presentationTimeUs) ?: lastCallback ?: return null
        val flaggedKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        return if (mime == MIME_AVC) {
            val payload = AndroidAnnexB.payload(raw, flaggedKey, paramSets)
            val nals = AndroidAnnexB.split(payload)
            // A sync frame from the stream's own header pass may carry new SPS/PPS: keep them.
            nals.filter { AndroidAnnexB.type(it) == AndroidAnnexB.NAL_SPS || AndroidAnnexB.type(it) == AndroidAnnexB.NAL_PPS }
                .takeIf { s -> s.any { AndroidAnnexB.type(it) == AndroidAnnexB.NAL_SPS } }?.let { paramSets = it }
            val key = nals.any { AndroidAnnexB.type(it) == AndroidAnnexB.NAL_IDR }
            val temporal = AndroidAnnexB.temporalLayer(nals)
            EncodedChunk(Layer(0, temporal, 0), payload, key) to cb
        } else {
            AndroidAv1.parse(raw)?.firstOrNull { it.type == AndroidAv1.OBU_SEQUENCE_HEADER }?.let { av1SequenceHeader = it }
            val (payload, temporal) = AndroidAv1.payload(raw, flaggedKey, av1SequenceHeader)
            EncodedChunk(Layer(0, temporal, 0), payload, flaggedKey) to cb
        }
    }

    /** Fills free input buffers with pending frames. Under [lock]. */
    private fun feed() {
        val c = codec ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val p = pending.removeFirst()
            val index = freeInputs.removeFirst()
            try {
                val image = c.getInputImage(index)
                if (image != null) I420.write(p.frame, image)
                else I420.writeBuffer(p.frame, c.getInputBuffer(index)!!, inputColorFormat, inputStride, inputSliceHeight)
                if (p.forceKeyframe) c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                // Presentation times must rise; a source restart may reset its clock.
                val pts = if (p.frame.timestampUs > lastPtsUs) p.frame.timestampUs else lastPtsUs + 1
                lastPtsUs = pts
                callbacks[pts] = p.onChunk
                lastCallback = p.onChunk
                if (callbacks.size > 64) callbacks.keys.minOrNull()?.let { callbacks.remove(it) }
                // The written picture spans the codec's strides, so the size is the buffer's
                // (read after the Image is done with: asking for it invalidates the Image).
                val size = c.getInputBuffer(index)?.capacity() ?: (p.frame.width * p.frame.height * 3 / 2)
                c.queueInputBuffer(index, 0, size, pts, 0)
            } catch (e: Exception) {
                Log.e(TAG, "encoder input failed", e)
            }
        }
    }

    private fun release() {
        codec?.let { c -> runCatching { c.stop() }; c.release() }
        codec = null
        pending.clear()
        freeInputs.clear()
        callbacks.clear()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            release()
        }
        thread.quitSafely()
    }
}

/**
 * The decoder. Configured once at a nominal size: the stream's own sequence
 * parameters set the real one, and the output Image carries it, so a publisher
 * that changes size needs no new decoder.
 */
internal class AndroidVideoDecoder(private val info: MediaCodecInfo, private val mime: String) : VideoDecoder {
    private class Pending(val bytes: ByteArray, val onFrame: (VideoFrame) -> Unit)

    private val lock = Any()
    private val thread = HandlerThread("ciris-call-vdec").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec = MediaCodec.createByCodecName(info.name)
    private val pending = ArrayDeque<Pending>()
    private val freeInputs = ArrayDeque<Int>()
    private val callbacks = HashMap<Long, (VideoFrame) -> Unit>()
    private var lastCallback: ((VideoFrame) -> Unit)? = null
    private var pts = 0L
    private var outFormat: MediaFormat? = null
    private var closed = false

    init {
        val format = MediaFormat.createVideoFormat(mime, 640, 480).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_MAX_WIDTH, 1920)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, 1920)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                synchronized(lock) {
                    if (closed) return
                    freeInputs.addLast(index)
                    feed()
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val out: Pair<VideoFrame, (VideoFrame) -> Unit>?
                synchronized(lock) {
                    if (closed) return
                    out = try {
                        val cb = callbacks.remove(info.presentationTimeUs) ?: lastCallback
                        val frame = if (info.size > 0 || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) readFrame(index, info) else null
                        if (frame != null && cb != null) frame to cb else null
                    } catch (e: Exception) {
                        Log.e(TAG, "decoder output failed", e); null
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                    }
                }
                out?.let { (f, cb) -> cb(f) }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "decoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                synchronized(lock) { outFormat = format }
            }
        }, handler)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    private fun readFrame(index: Int, info: MediaCodec.BufferInfo): VideoFrame? {
        codec.getOutputImage(index)?.use { return I420.read(it, info.presentationTimeUs) }
        val f = outFormat ?: codec.outputFormat
        val buf = codec.getOutputBuffer(index) ?: return null
        buf.position(info.offset)
        val w = f.getInteger(MediaFormat.KEY_WIDTH)
        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
        val crop = if (f.containsKey("crop-left")) {
            Rect(f.getInteger("crop-left"), f.getInteger("crop-top"), f.getInteger("crop-right") + 1, f.getInteger("crop-bottom") + 1)
        } else Rect(0, 0, w, h)
        return I420.readBuffer(
            buf, f.intOr(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar),
            w, h, f.intOr(MediaFormat.KEY_STRIDE, w), f.intOr(MediaFormat.KEY_SLICE_HEIGHT, h), crop, info.presentationTimeUs,
        )
    }

    override fun decode(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
        synchronized(lock) {
            if (closed || bytes.isEmpty()) return
            pending.addLast(Pending(bytes, onFrame))
            feed()
        }
    }

    /** Under [lock]. Every chunk is decoded: dropping one breaks the reference chain until the next keyframe. */
    private fun feed() {
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val p = pending.removeFirst()
            val index = freeInputs.removeFirst()
            try {
                val buf = codec.getInputBuffer(index) ?: continue
                buf.clear()
                if (p.bytes.size > buf.capacity()) {
                    Log.w(TAG, "decoder input ${p.bytes.size} > buffer ${buf.capacity()}; skipped")
                    codec.queueInputBuffer(index, 0, 0, pts, 0)
                    continue
                }
                buf.put(p.bytes)
                val t = pts++ * 1000
                callbacks[t] = p.onFrame
                lastCallback = p.onFrame
                if (callbacks.size > 64) callbacks.keys.minOrNull()?.let { callbacks.remove(it) }
                codec.queueInputBuffer(index, 0, p.bytes.size, t, 0)
            } catch (e: Exception) {
                Log.e(TAG, "decoder input failed", e)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { codec.stop() }
            codec.release()
            pending.clear()
        }
        thread.quitSafely()
    }

    companion object {
        fun create(codecId: Int): AndroidVideoDecoder? {
            val mime = when (codecId) {
                CallVideoCodec.TEMPORAL_LAYERED -> MIME_AVC
                CallVideoCodec.AV1_SVC -> MIME_AV1
                else -> return null
            }
            val candidates = codecsFor(mime, encoder = false).filter { info ->
                runCatching { info.getCapabilitiesForType(mime).colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) }
                    .getOrDefault(false)
            }
            // AV1: hardware only (no dav1d here). H.264: hardware first, the platform's software decoder otherwise.
            val chosen = if (mime == MIME_AV1) candidates.firstOrNull { isHardware(it) }
            else candidates.firstOrNull { isHardware(it) } ?: candidates.firstOrNull()
            chosen ?: return null
            return runCatching { AndroidVideoDecoder(chosen, mime) }.getOrElse {
                Log.e(TAG, "decoder ${chosen.name} could not start", it)
                // A hardware decoder that refuses is not the end for H.264: the software one stays.
                if (mime == MIME_AVC) candidates.firstOrNull { it !== chosen && !isHardware(it) }
                    ?.let { sw -> runCatching { AndroidVideoDecoder(sw, mime) }.getOrNull() } else null
            }
        }
    }
}

private fun MediaFormat.intOr(key: String, default: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default
