package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.Layer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_I
import org.bytedeco.ffmpeg.global.avutil.AV_PICTURE_TYPE_NONE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.av_make_q
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.ffmpeg.swscale.SwsFilter
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer

/**
 * Video on desktop.
 *
 * AV1, codec `0x01`, is FFmpeg 7.1 through JavaCPP (bytedeco 1.5.11, the LGPL
 * build), called through the raw libavcodec API, not javacv: SVT-AV1
 * (`libsvtav1`, low-delay, preset 12) to encode, and `libdav1d` if this build
 * has it, else `libaom-av1`, to decode.
 *
 * H.264, codec `0x04`, is Cisco's OpenH264 library and nothing else. It is not
 * in this app: the person turns it on, it is downloaded from Cisco onto their
 * machine, and they can turn it off again ([h264Download]). The FFmpeg in this
 * app can also encode and decode H.264, and this file does not ask it to. With
 * H.264 off, this desktop neither sends nor shows H.264.
 *
 * Encoder: AV1 where a probe at the requested size shows SVT-AV1 keeps up in
 * real time; else H.264 if it is on; else none, and the call is audio only from
 * this side. Output goes through [Av1Packetizer] / [H264Packetizer] so it matches
 * [CallVideoCodec] whatever the library emits. One spatial and one temporal
 * layer: [Layer.BASE].
 */
actual fun createVideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder? {
    if (Av1Probe.realTime(width, height, fps, bitrate)) {
        createDesktopVideoEncoder(DesktopVideoCodec.AV1, width, height, fps, bitrate)?.let { return it }
    }
    return createDesktopVideoEncoder(DesktopVideoCodec.H264, width, height, fps, bitrate)
}

actual fun createVideoDecoder(codecId: Int): VideoDecoder? = when (codecId) {
    CallVideoCodec.TEMPORAL_LAYERED -> DesktopH264Holder.get()?.api?.decoder()
    CallVideoCodec.AV1_SVC -> if (!Ffmpeg.loaded) null else {
        val codec = avcodec_find_decoder_by_name("libdav1d") ?: avcodec_find_decoder_by_name("libaom-av1")
        try { codec?.let { FfmpegVideoDecoder(it) } } catch (e: IllegalStateException) { null }
    }
    else -> null
}

/** The desktop encoders by name, so a caller (or a test) can pick one rather than take the probe's choice. */
enum class DesktopVideoCodec(val codecId: Int, val codecName: String) {
    AV1(CallVideoCodec.AV1_SVC, "av1"),
    H264(CallVideoCodec.TEMPORAL_LAYERED, "h264"),
}

/**
 * That encoder at that size. Null for AV1 if this FFmpeg build cannot open
 * SVT-AV1, and for H.264 unless the person has OpenH264 turned on.
 */
fun createDesktopVideoEncoder(codec: DesktopVideoCodec, width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder? = when (codec) {
    DesktopVideoCodec.H264 -> DesktopH264Holder.get()?.api?.encoder(width, height, fps, bitrate)
    DesktopVideoCodec.AV1 -> if (!Ffmpeg.loaded) null else try {
        FfmpegVideoEncoder(width, height, fps, bitrate)
    } catch (e: IllegalStateException) {
        System.err.println("[call] video encoder $SVT_AV1 ${width}x$height: ${e.message}")
        null
    }
}

/** Which encoder [createVideoEncoder] picks at this size on this machine, and why. For logs and the report. */
fun desktopVideoEncoderChoice(width: Int, height: Int, fps: Int, bitrate: Int): String {
    val h264 = if (DesktopH264Holder.get()?.api != null) "h264 (OpenH264 is on)" else "none (H.264 is off)"
    return if (!Ffmpeg.loaded) "$h264; FFmpeg did not load: ${Ffmpeg.error}"
    else if (Av1Probe.realTime(width, height, fps, bitrate)) "av1 (${Av1Probe.describe(width, height, fps)})"
    else "$h264; ${Av1Probe.describe(width, height, fps)}"
}

private const val SVT_AV1 = "libsvtav1"

internal object Ffmpeg {
    var error: String? = null
        private set

    val loaded: Boolean by lazy {
        try {
            av_log_set_level(AV_LOG_ERROR)
            true
        } catch (t: Throwable) { // UnsatisfiedLinkError, NoClassDefFoundError: no natives for this OS
            error = t.toString()
            false
        }
    }
}

/**
 * Does SVT-AV1 keep up at this size? Encodes a second of the test pattern once per
 * size and times it: real time means every frame in under half its interval, so
 * capture, scaling and the rest of the call have the other half. Cached.
 */
internal object Av1Probe {
    private val results = mutableMapOf<String, Pair<Boolean, String>>()

    fun realTime(width: Int, height: Int, fps: Int, bitrate: Int): Boolean = probe(width, height, fps, bitrate).first

    fun describe(width: Int, height: Int, fps: Int): String =
        synchronized(results) { results["${width}x$height@$fps"]?.second ?: "not probed" }

    private fun probe(width: Int, height: Int, fps: Int, bitrate: Int): Pair<Boolean, String> = synchronized(results) {
        results.getOrPut("${width}x$height@$fps") {
            if (!Ffmpeg.loaded) return@getOrPut false to "FFmpeg did not load"
            val enc = createDesktopVideoEncoder(DesktopVideoCodec.AV1, width, height, fps, bitrate) as FfmpegVideoEncoder?
                ?: return@getOrPut false to "libsvtav1 would not open"
            val pattern = TestPatternSource(CoroutineScope(Dispatchers.Unconfined), width, height, fps)
            val frames = (0 until fps.coerceAtLeast(10)).map { pattern.nextFrame() }
            try {
                enc.encode(frames[0], true) { } // warm-up: the first frame carries setup cost
                val t0 = System.nanoTime()
                for (f in frames.drop(1)) enc.encode(f, false) { }
                val behind = enc.sent - enc.received
                // Until the last chunk is out: the wall time the frames really cost.
                enc.flush { }
                val perFrameMs = (System.nanoTime() - t0) / 1e6 / (frames.size - 1)
                val budgetMs = 1000.0 / fps / 2
                // Real time: inside half the frame interval. ("Behind" is what a back-to-back
                // burst leaves queued; at the real frame rate it is the one held frame.)
                val ok = perFrameMs < budgetMs
                ok to "svt-av1 %.1f ms/frame, budget %.1f ms, %d queued after a burst".format(perFrameMs, budgetMs, behind)
            } finally {
                enc.close()
            }
        }
    }
}

/** Copies an I420 [VideoFrame] into a writable YUV420P AVFrame, row by row (FFmpeg pads its lines). */
internal fun VideoFrame.copyInto(dst: AVFrame) {
    fun plane(i: Int, src: ByteArray, w: Int, h: Int) {
        val p = dst.data(i)
        val ls = dst.linesize(i)
        for (row in 0 until h) p.position(row.toLong() * ls).put(src, row * w, w)
        p.position(0)
    }
    plane(0, y, width, height)
    plane(1, u, width / 2, height / 2)
    plane(2, v, width / 2, height / 2)
}

/** A YUV420P AVFrame to an I420 [VideoFrame], cropped to even dimensions. */
internal fun AVFrame.toVideoFrame(timestampUs: Long): VideoFrame {
    val w = width() and 1.inv()
    val h = height() and 1.inv()
    fun plane(i: Int, pw: Int, ph: Int): ByteArray {
        val out = ByteArray(pw * ph)
        val p = data(i)
        val ls = linesize(i)
        for (row in 0 until ph) p.position(row.toLong() * ls).get(out, row * pw, pw)
        p.position(0)
        return out
    }
    return VideoFrame(w, h, plane(0, w, h), plane(1, w / 2, h / 2), plane(2, w / 2, h / 2), timestampUs)
}

/** Converts any decoded picture to YUV420P at a given size; one per stream, the context is cached. */
internal class ToI420 {
    private var sws: SwsContext? = null
    private var out: AVFrame? = null

    fun convert(src: AVFrame, dstW: Int, dstH: Int, timestampUs: Long): VideoFrame {
        if (src.format() == AV_PIX_FMT_YUV420P && src.width() == dstW && src.height() == dstH) return src.toVideoFrame(timestampUs)
        val o = out?.takeIf { it.width() == dstW && it.height() == dstH } ?: run {
            out?.let { av_frame_free(it) }
            av_frame_alloc().also {
                it.format(AV_PIX_FMT_YUV420P).width(dstW).height(dstH)
                check(av_frame_get_buffer(it, 32) >= 0) { "frame buffer" }
                out = it
            }
        }
        sws = sws_getCachedContext(
            sws, src.width(), src.height(), src.format(), dstW, dstH, AV_PIX_FMT_YUV420P,
            SWS_BILINEAR, null as SwsFilter?, null as SwsFilter?, null as DoublePointer?,
        ) ?: error("no scaler from pixel format ${src.format()}")
        sws_scale(sws, src.data(), src.linesize(), 0, src.height(), o.data(), o.linesize())
        return o.toVideoFrame(timestampUs)
    }

    fun close() {
        sws?.let { sws_freeContext(it) }
        sws = null
        out?.let { av_frame_free(it) }
        out = null
    }
}

private fun BytePointer.bytes(size: Int): ByteArray = ByteArray(size).also { position(0).get(it, 0, size) }

/** AV1 through SVT-AV1. The only encoder this app asks FFmpeg for. */
internal class FfmpegVideoEncoder(
    private val width: Int,
    private val height: Int,
    fps: Int,
    bitrate: Int,
) : VideoEncoder {
    override val codecId = DesktopVideoCodec.AV1.codecId
    override val codecName = DesktopVideoCodec.AV1.codecName

    private val ctx: AVCodecContext
    private val frame: AVFrame
    private val packet: AVPacket = av_packet_alloc()
    private val av1 = Av1Packetizer()
    private var pts = 0L
    private var closed = false

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "I420 needs even dimensions" }
        val c: AVCodec = avcodec_find_encoder_by_name(SVT_AV1) ?: error("$SVT_AV1 not in this FFmpeg build")
        ctx = avcodec_alloc_context3(c) ?: error("context")
        ctx.width(width).height(height).pix_fmt(AV_PIX_FMT_YUV420P)
        ctx.time_base(av_make_q(1, fps)).framerate(av_make_q(fps, 1))
        ctx.bit_rate(bitrate.toLong()).rc_buffer_size(bitrate)
        // Keyframes are the caller's (CallVideo forces one every two seconds and on request).
        ctx.gop_size(fps * 60).max_b_frames(0)
        val opts = AVDictionary(null)
        av_dict_set(opts, "preset", "12", 0)
        // Low delay (no reordering, no lookahead), CBR (SVT-AV1 2.2 takes a max
        // rate only with CRF, so no rc_max_rate here), and a forced keyframe is a
        // real KEY_FRAME (closed GOP), not an intra-only frame.
        av_dict_set(opts, "svtav1-params", "pred-struct=1:rc=2:lookahead=0:irefresh-type=2:scd=0", 0)
        val r = avcodec_open2(ctx, c, opts)
        av_dict_free(opts)
        if (r < 0) {
            avcodec_free_context(ctx)
            av_packet_free(packet)
            error("$SVT_AV1 would not open ($r)")
        }
        frame = av_frame_alloc()
        frame.format(AV_PIX_FMT_YUV420P).width(width).height(height)
        check(av_frame_get_buffer(frame, 32) >= 0) { "frame buffer" }
        // Out-of-band headers, where the library gives them, seed the packetizers.
        if (ctx.extradata_size() > 0) {
            val extra = ctx.extradata().bytes(ctx.extradata_size())
            if (extra[0].toInt() and 0x80 != 0) av1.onDescription(extra) else av1.temporalUnit(extra, false)
        }
    }

    @Synchronized
    override fun encode(frame: VideoFrame, forceKeyframe: Boolean, onChunk: (EncodedChunk) -> Unit) {
        if (closed) return
        require(frame.width == width && frame.height == height) { "encoder is ${width}x$height, frame ${frame.width}x${frame.height}" }
        check(av_frame_make_writable(this.frame) >= 0) { "frame not writable" }
        frame.copyInto(this.frame)
        this.frame.pts(pts++)
        this.frame.pict_type(if (forceKeyframe) AV_PICTURE_TYPE_I else AV_PICTURE_TYPE_NONE)
        if (avcodec_send_frame(ctx, this.frame) < 0) return
        sent++
        // SVT-AV1 holds one frame: FFmpeg 7.1's libsvtav1 wrapper polls SVT for output
        // only when it is handed a new frame, so frame N's chunk comes out on frame N+1's
        // call (one interval of latency; measured, waiting here never releases it).
        drain(onChunk)
    }

    /** Frames sent and chunks out: the probe reads how far behind the encoder runs. */
    internal var sent = 0L
        private set
    internal var received = 0L
        private set

    /** End of stream: every held frame's chunk, then the encoder takes no more. For the probe and tests. */
    @Synchronized
    internal fun flush(onChunk: (EncodedChunk) -> Unit) {
        if (closed) return
        if (avcodec_send_frame(ctx, null as AVFrame?) >= 0) drain(onChunk)
    }

    private fun drain(onChunk: (EncodedChunk) -> Unit) {
        while (avcodec_receive_packet(ctx, packet) == 0) {
            received++
            val data = packet.data().bytes(packet.size())
            val flaggedKey = packet.flags() and AV_PKT_FLAG_KEY != 0
            av_packet_unref(packet)
            val payload = av1.temporalUnit(data, flaggedKey)
            // The witness is the bytes, as a receiver will read them.
            onChunk(EncodedChunk(Layer.BASE, payload, Keyframes.isKeyframe(codecId, payload)))
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // End of stream first: SVT-AV1 complains when torn down mid-stream.
        if (avcodec_send_frame(ctx, null as AVFrame?) >= 0) {
            while (avcodec_receive_packet(ctx, packet) == 0) av_packet_unref(packet)
        }
        avcodec_free_context(ctx)
        av_frame_free(frame)
        av_packet_free(packet)
    }
}

internal class FfmpegVideoDecoder(codec: AVCodec) : VideoDecoder {
    private val ctx: AVCodecContext = avcodec_alloc_context3(codec) ?: error("context")
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private val convert = ToI420()
    private var closed = false

    init {
        // One thread and low delay: a picture out for every chunk in, no frame-threading latency.
        ctx.thread_count(1)
        ctx.flags(ctx.flags() or AV_CODEC_FLAG_LOW_DELAY)
        if (avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
            avcodec_free_context(ctx)
            error("decoder would not open")
        }
    }

    @Synchronized
    override fun decode(layer: Layer, bytes: ByteArray, onFrame: (VideoFrame) -> Unit) {
        if (closed || bytes.isEmpty()) return
        if (av_new_packet(packet, bytes.size) < 0) return
        packet.data().position(0).put(bytes, 0, bytes.size)
        val sent = avcodec_send_packet(ctx, packet)
        av_packet_unref(packet)
        if (sent < 0) return // a damaged chunk: skip it, the next keyframe recovers
        while (avcodec_receive_frame(ctx, frame) == 0) {
            val w = frame.width() and 1.inv()
            val h = frame.height() and 1.inv()
            if (w > 0 && h > 0) onFrame(convert.convert(frame, w, h, System.nanoTime() / 1000))
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        convert.close()
        avcodec_free_context(ctx)
        av_frame_free(frame)
        av_packet_free(packet)
    }
}
