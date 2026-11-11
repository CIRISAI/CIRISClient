package ai.ciris.mobile.shared.calls.media

import ai.ciris.mobile.shared.calls.CallFrames
import ai.ciris.mobile.shared.calls.CallSession
import ai.ciris.mobile.shared.calls.Layer
import ai.ciris.mobile.shared.calls.OutgoingFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.concurrent.Volatile
import kotlin.math.sin
import kotlin.time.TimeSource

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
 * member's 0xFF frames → their own [AudioPlayout] → their own decoder → [sink].
 *
 * Received packets are not played on arrival. Each member has a playout buffer,
 * and one clock takes a packet from each every 20 ms; when a member's buffer is
 * empty at its turn, the decoder conceals (Opus PLC), up to
 * [AudioPlayout.MAX_CONCEALED] in a row. `chunk_seq` only rejects repeats: it
 * counts the stream's video frames too, so it cannot say how much sound is missing.
 * A new stream from the same member resets that member's decoder and buffer.
 *
 * Receive, playout and decode all run on one thread of their own, so a slow
 * screen never holds up sound and no two of them touch a decoder at once.
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
    private var clock: Job? = null
    private val inbound = mutableMapOf<String, Inbound>()
    private val audioThread = Dispatchers.Default.limitedParallelism(1)

    @Volatile var muted: Boolean = false

    private class Inbound(val streamId: ByteArray, val decoder: OpusDecoder, var lastSeq: ULong) {
        val playout = AudioPlayout()
    }

    fun start() {
        if (encoder != null) return
        val enc = newEncoder().also { encoder = it }
        source.start { pcm ->
            if (!muted) enc.encode(pcm) { packet -> session.publish(OutgoingFrame(CallFrames.CODEC_OPAQUE, Layer.BASE, packet)) }
        }
        listener = scope.launch(audioThread) {
            session.frames.collect { f ->
                if (f.codecId != CallFrames.CODEC_OPAQUE) return@collect
                receive(f.publisher, f.streamId, f.chunkSeq, f.payload)
            }
        }
        clock = scope.launch(audioThread) {
            var started = TimeSource.Monotonic.markNow()
            var ticks = 0L
            while (isActive) {
                ticks++
                val wait = ticks * CallAudioFormat.FRAME_MS - started.elapsedNow().inWholeMilliseconds
                if (wait > 0) {
                    delay(wait)
                } else if (wait < -MAX_CLOCK_LAG_MS) {
                    // The process was held up (a suspended tab, a stopped debugger): do not
                    // replay the lost time as a burst; the buffers skip ahead on their own.
                    started = TimeSource.Monotonic.markNow()
                    ticks = 0
                }
                tick()
            }
        }
    }

    internal fun receive(publisher: String, streamId: ByteArray, seq: ULong, packet: ByteArray) {
        var from = inbound[publisher]
        if (from == null || !from.streamId.contentEquals(streamId)) {
            from?.decoder?.close()
            from = Inbound(streamId, newDecoder(), seq)
            inbound[publisher] = from
        } else {
            if (seq <= from.lastSeq) return // a repeat, or one from before a replay
            from.lastSeq = seq
        }
        from.playout.offer(packet)
    }

    /** One 20 ms step of the playout clock: each member's next packet, or concealment, to the sink. */
    internal fun tick() {
        for ((publisher, from) in inbound) {
            when (val step = from.playout.next()) {
                is AudioPlayout.Step.Packet -> from.decoder.decode(step.bytes) { sink.play(publisher, it) }
                AudioPlayout.Step.Conceal -> from.decoder.decode(null) { sink.play(publisher, it) }
                AudioPlayout.Step.Silence -> Unit
            }
        }
    }

    /** A member left: their decoder and their slot in the mix go with them. */
    fun forget(publisher: String) {
        scope.launch(audioThread) { forgetNow(publisher) }
    }

    internal fun forgetNow(publisher: String) {
        inbound.remove(publisher)?.decoder?.close()
        sink.remove(publisher)
    }

    fun stop() {
        source.stop()
        listener?.cancel()
        listener = null
        clock?.cancel()
        clock = null
        encoder?.close()
        encoder = null
        // On the audio thread, after any step already running there, and whether or
        // not the call's scope is cancelled next: a decoder is never closed mid-decode.
        scope.launch(audioThread + NonCancellable) {
            inbound.values.forEach { it.decoder.close() }
            inbound.clear()
            sink.stop()
        }
    }

    companion object {
        /** At most this many 20 ms frames of concealment in a row: 100 ms. */
        const val MAX_CONCEALED = AudioPlayout.MAX_CONCEALED
        private const val MAX_CLOCK_LAG_MS = 200
    }
}
