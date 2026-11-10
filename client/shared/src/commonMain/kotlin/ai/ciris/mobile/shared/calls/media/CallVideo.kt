package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.CallFrames
import ai.ciris.mobile.shared.calls.CallSession
import ai.ciris.mobile.shared.calls.Layer
import ai.ciris.mobile.shared.calls.OutgoingFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Call video (CSD-134). A device that encodes AV1 in real time publishes `0x01`
 * (AV1 SVC); one that cannot publishes `0x04` (H.264, temporal layers at spatial
 * 0; the stream's binding names the codec). Every client MUST decode `0x01` and
 * SHOULD decode `0x04` (CC, 2026-11-10). From Edge 45.1 a whole encoded frame
 * goes to the node and Edge fragments it; until then a frame over the budget is
 * refused, so video is not usable on v44 budgets.
 */
object CallVideoCodec {
    const val AV1_SVC = CallFrames.CODEC_AV1_SVC
    /** CC 5.3.3.2.1: temporal-layered, one spatial layer (H.264, VP8, VP9). */
    const val TEMPORAL_LAYERED = 0x04
}

/**
 * One picture, as planar I420 (Y full size, U and V at half size each way): what
 * encoders take and decoders give. [toArgb] is for the screen.
 */
class VideoFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val timestampUs: Long,
) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "I420 needs even dimensions" }
        require(y.size == width * height && u.size == width * height / 4 && v.size == u.size) { "plane sizes" }
    }

    /** BT.601 limited range to ARGB, for a tile. */
    fun toArgb(): IntArray {
        val out = IntArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val yy = (y[row * width + col].toInt() and 0xFF) - 16
                val ci = (row / 2) * (width / 2) + col / 2
                val uu = (u[ci].toInt() and 0xFF) - 128
                val vv = (v[ci].toInt() and 0xFF) - 128
                val c = 298 * yy
                val r = ((c + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((c - 100 * uu - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((c + 516 * uu + 128) shr 8).coerceIn(0, 255)
                out[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}

/** What a video stream shows: the camera, a whole screen, one window, or a test pattern in the runner. */
enum class VideoKind { CAMERA, SCREEN, WINDOW, TEST_PATTERN }

interface VideoSource {
    val kind: VideoKind
    fun start(onFrame: (VideoFrame) -> Unit)
    fun stop()
}

/** One encoded chunk: one layer's data for one frame (CSD-134 §6). */
class EncodedChunk(val layer: Layer, val bytes: ByteArray, val keyframe: Boolean)

interface VideoEncoder {
    /** [CallVideoCodec.AV1_SVC] or [CallVideoCodec.TEMPORAL_LAYERED]. */
    val codecId: Int
    /** The binding's codec token for this stream: `av1`, `h264`, `vp8` or `vp9`. */
    val codecName: String
    fun encode(frame: VideoFrame, forceKeyframe: Boolean = false): List<EncodedChunk>
    fun close()
}

interface VideoDecoder {
    /** One chunk in; a picture out when one is complete, else null. Null input asks for concealment. */
    fun decode(layer: Layer, bytes: ByteArray?): VideoFrame?
    fun close()
}

/**
 * The platform's video codecs. [createVideoEncoder] returns AV1 where the device
 * encodes it in real time, else H.264; null where neither is built yet.
 * [createVideoDecoder] returns null for a codec this platform cannot decode.
 */
expect fun createVideoEncoder(width: Int, height: Int, fps: Int, bitrate: Int): VideoEncoder?
expect fun createVideoDecoder(codecId: Int): VideoDecoder?

/**
 * A moving test pattern, for the five-platform runner where there is no camera:
 * colour bars with a bright square that sweeps across, so a receiver can check
 * the picture both arrived and moved. Pure Kotlin, the same on every platform.
 */
class TestPatternSource(
    private val scope: CoroutineScope,
    val width: Int = 320,
    val height: Int = 180,
    val fps: Int = 15,
) : VideoSource {
    override val kind = VideoKind.TEST_PATTERN
    private var job: Job? = null
    private var n = 0L

    override fun start(onFrame: (VideoFrame) -> Unit) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                onFrame(nextFrame())
                delay(1000L / fps)
            }
        }
    }

    internal fun nextFrame(): VideoFrame {
        val y = ByteArray(width * height)
        val u = ByteArray(width * height / 4)
        val v = ByteArray(width * height / 4)
        val bars = intArrayOf(235, 210, 170, 145, 106, 81, 41, 16) // luma of the eight bars
        val barW = width / bars.size
        for (row in 0 until height) for (col in 0 until width) {
            y[row * width + col] = bars[(col / barW).coerceAtMost(bars.size - 1)].toByte()
        }
        for (i in u.indices) { u[i] = 128.toByte(); v[i] = 128.toByte() }
        // The sweeping square: position is a function of the frame number.
        val side = height / 4
        val x0 = ((n * 4) % (width - side)).toInt()
        val y0 = (height - side) / 2
        for (row in y0 until y0 + side) for (col in x0 until x0 + side) y[row * width + col] = 255.toByte()
        n++
        return VideoFrame(width, height, y, u, v, n * 1_000_000L / fps)
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * Wires a call's video: [source] → [encoder] → [CallSession.publish], and every
 * video frame from other members → that stream's own decoder → [render].
 *
 * Decoders are kept per stream, not per member: a member's camera and their
 * screen share are two streams and two tiles (CSD-134). A keyframe is forced at
 * the start, and when [requestKeyframe] is called after a receiver joins.
 */
class CallVideo(
    private val session: CallSession,
    private val source: VideoSource?,
    private val encoder: VideoEncoder?,
    private val scope: CoroutineScope,
    private val render: (publisher: String, streamId: ByteArray, frame: VideoFrame) -> Unit,
    private val newDecoder: (codecId: Int) -> VideoDecoder? = ::createVideoDecoder,
    /** Says, per stream, that this device cannot decode its codec (the tile shows audio only). */
    private val undecodable: (publisher: String, codecId: Int) -> Unit = { _, _ -> },
) {
    private var listener: Job? = null
    private val decoders = mutableMapOf<String, Pair<Int, VideoDecoder>>()
    private var keyframeDue = true

    var cameraOff: Boolean = false

    fun requestKeyframe() { keyframeDue = true }

    fun start() {
        if (encoder != null && source != null) {
            source.start { frame ->
                if (cameraOff) return@start
                val force = keyframeDue
                keyframeDue = false
                for (chunk in encoder.encode(frame, force)) {
                    session.publish(OutgoingFrame(encoder.codecId, chunk.layer, chunk.bytes))
                }
            }
        }
        listener = scope.launch {
            session.frames.collect { f ->
                if (f.codecId == CallFrames.CODEC_OPAQUE) return@collect
                receive(f.publisher, f.streamId, f.codecId, f.layer, f.payload)
            }
        }
    }

    internal fun receive(publisher: String, streamId: ByteArray, codecId: Int, layer: Layer, bytes: ByteArray) {
        val key = streamKey(streamId)
        var entry = decoders[key]
        if (entry == null || entry.first != codecId) {
            entry?.second?.close()
            val dec = newDecoder(codecId)
            if (dec == null) {
                undecodable(publisher, codecId)
                return
            }
            entry = codecId to dec
            decoders[key] = entry
        }
        entry.second.decode(layer, bytes)?.let { render(publisher, streamId, it) }
    }

    fun stop() {
        source?.stop()
        listener?.cancel()
        listener = null
        encoder?.close()
        decoders.values.forEach { it.second.close() }
        decoders.clear()
    }

    private fun streamKey(id: ByteArray) = id.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
