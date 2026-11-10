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
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Call audio (CSD-134): 48 kHz mono, 20 ms frames, one Opus packet per frame in
 * codec 0xFF at the base layer, as agreed with CIRISEdge and CIRISServer.
 */
object CallAudioFormat {
    const val SAMPLE_RATE = 48_000
    const val FRAME_MS = 20
    /** Samples in one 20 ms mono frame. */
    const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
    /** Big enough for any 20 ms Opus packet at the agreed 24–32 kbit/s, with room. */
    const val MAX_PACKET_BYTES = 400
    const val BITRATE = 28_000
}

/** Where this device's sound comes from: the microphone, or a test tone in the runner. */
interface AudioSource {
    /** Calls [onFrame] with [CallAudioFormat.FRAME_SAMPLES] samples every 20 ms until [stop]. */
    fun start(onFrame: (ShortArray) -> Unit)
    fun stop()
}

/** Where other members' sound goes. One stream per publisher; the sink mixes them. */
interface AudioSink {
    fun play(publisher: String, pcm: ShortArray)
    /** A member's stream ended: release whatever the sink holds for it. */
    fun remove(publisher: String)
    fun stop()
}

/**
 * Push-style, because the platform codecs are asynchronous (WebCodecs, AudioToolbox
 * converters, MediaCodec): input goes in, output arrives on [onPacket] when it is
 * ready, possibly later and on another thread. A synchronous codec (Concentus) calls
 * it before [encode] returns. Outputs come in input order.
 */
interface OpusEncoder {
    fun encode(pcm: ShortArray, onPacket: (ByteArray) -> Unit)
    fun close()
}

interface OpusDecoder {
    /**
     * Decode one packet, or conceal a lost one when [packet] is null, and hand
     * [CallAudioFormat.FRAME_SAMPLES] samples to [onPcm]. Outputs come in input order.
     */
    fun decode(packet: ByteArray?, onPcm: (ShortArray) -> Unit)
    fun close()
}

/** The platform's Opus: Concentus on the JVM (Android and desktop), AudioToolbox on iOS, WebCodecs on the web. */
expect fun createOpusEncoder(): OpusEncoder
expect fun createOpusDecoder(): OpusDecoder

/** True where this platform's Opus is built. Where it is false, a call has no audio and the screen says so. */
expect val opusAvailable: Boolean

/**
 * A test tone, for the five-platform runner where there is no microphone: a
 * steady sine, so a receiver can check it heard the frequency it was sent.
 * Pure Kotlin, so every platform runs the same source.
 */
class ToneSource(
    private val scope: CoroutineScope,
    private val hz: Double = 440.0,
    private val amplitude: Short = 8_000,
) : AudioSource {
    private var job: Job? = null
    private var phase = 0.0

    override fun start(onFrame: (ShortArray) -> Unit) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                onFrame(nextFrame())
                delay(CallAudioFormat.FRAME_MS.toLong())
            }
        }
    }

    internal fun nextFrame(): ShortArray {
        val step = 2 * PI * hz / CallAudioFormat.SAMPLE_RATE
        return ShortArray(CallAudioFormat.FRAME_SAMPLES) {
            val v = (sin(phase) * amplitude).roundToInt().toShort()
            phase += step
            if (phase > 2 * PI) phase -= 2 * PI
            v
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * Wires a call's audio: [source] → Opus → [CallSession.publish], and every
 * member's 0xFF frames → their own decoder → [sink].
 *
 * Edge hands frames over in `chunk_seq` order with holes and never re-fills one
 * (CSD-134 §6), so a jump in a publisher's `chunk_seq` is concealed with one
 * Opus PLC frame per missing chunk, capped so a long silence is not a burst of
 * invented sound. A new stream from the same member resets that member's decoder.
 */
class CallAudio(
    private val session: CallSession,
    private val source: AudioSource,
    private val sink: AudioSink,
    private val scope: CoroutineScope,
    private val newEncoder: () -> OpusEncoder = ::createOpusEncoder,
    private val newDecoder: () -> OpusDecoder = ::createOpusDecoder,
) {
    private var encoder: OpusEncoder? = null
    private var listener: Job? = null
    private val decoders = mutableMapOf<String, Inbound>()

    var muted: Boolean = false

    private class Inbound(val streamId: ByteArray, val decoder: OpusDecoder, var lastSeq: ULong)

    fun start() {
        if (encoder != null) return
        val enc = newEncoder().also { encoder = it }
        source.start { pcm ->
            if (!muted) enc.encode(pcm) { packet -> session.publish(OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, packet)) }
        }
        listener = scope.launch {
            session.frames.collect { f ->
                if (f.codecId != CallFrames.CODEC_OPAQUE) return@collect
                receive(f.publisher, f.streamId, f.chunkSeq, f.payload)
            }
        }
    }

    internal fun receive(publisher: String, streamId: ByteArray, seq: ULong, packet: ByteArray) {
        var inbound = decoders[publisher]
        if (inbound == null || !inbound.streamId.contentEquals(streamId)) {
            inbound?.decoder?.close()
            inbound = Inbound(streamId, newDecoder(), seq - 1uL)
            decoders[publisher] = inbound
        }
        if (seq <= inbound.lastSeq) return // a duplicate or a late one: already concealed
        val missing = (seq - inbound.lastSeq - 1uL).coerceAtMost(MAX_CONCEALED.toULong()).toInt()
        val play: (ShortArray) -> Unit = { sink.play(publisher, it) }
        repeat(missing) { inbound.decoder.decode(null, play) }
        inbound.decoder.decode(packet, play)
        inbound.lastSeq = seq
    }

    /** A member left: their decoder and their slot in the mix go with them. */
    fun forget(publisher: String) {
        decoders.remove(publisher)?.decoder?.close()
        sink.remove(publisher)
    }

    fun stop() {
        source.stop()
        listener?.cancel()
        listener = null
        encoder?.close()
        encoder = null
        decoders.values.forEach { it.decoder.close() }
        decoders.clear()
        sink.stop()
    }

    companion object {
        /** At most this many 20 ms frames of concealment for one gap: 100 ms. */
        const val MAX_CONCEALED = 5
    }
}
