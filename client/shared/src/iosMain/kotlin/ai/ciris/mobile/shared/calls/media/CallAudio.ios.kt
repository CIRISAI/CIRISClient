@file:OptIn(ExperimentalForeignApi::class)

package ai.ciris.mobile.shared.calls.media

import kotlinx.cinterop.*
// Wildcards on purpose, as in QrScanner.ios.kt.
import platform.AVFAudio.*
import platform.Foundation.NSLock
import kotlin.concurrent.AtomicInt

/**
 * The iOS microphone for a call: AVAudioEngine's input with voice processing
 * on (Apple's echo canceller, noise suppression and AGC), converted to 48 kHz
 * mono Int16 and cut into exactly [CallAudioFormat.FRAME_SAMPLES]-sample frames.
 *
 * Requires NSMicrophoneUsageDescription and granted record permission
 * (CallMediaPlatform asks). Frames arrive on the audio engine's tap thread.
 */
fun iosMicrophoneSource(): AudioSource = IosMicrophoneSource()

/**
 * The iOS speaker for a call: one AVAudioPlayerNode per publisher, mixed by the
 * engine's main mixer. Shares the microphone's engine so the echo canceller
 * hears exactly what is played.
 */
fun iosSpeakerSink(): AudioSink = IosSpeakerSink()

/**
 * One AVAudioEngine for the whole call, because voice processing cancels the
 * echo of what THIS engine plays. Reference-counted by the source and the sink.
 */
internal object IosCallAudioEngine {
    private val lock = NSLock()
    private var users = 0
    private var engineOrNull: AVAudioEngine? = null

    /** Float32 mono 48 kHz: what the player nodes are connected with. */
    val playFormat: AVAudioFormat by lazy {
        AVAudioFormat(
            commonFormat = AVAudioPCMFormatFloat32,
            sampleRate = CallAudioFormat.SAMPLE_RATE.toDouble(),
            channels = 1u,
            interleaved = false,
        )
    }

    /** Int16 mono 48 kHz interleaved: what the call's frames are. */
    val callFormat: AVAudioFormat by lazy {
        AVAudioFormat(
            commonFormat = AVAudioPCMFormatInt16,
            sampleRate = CallAudioFormat.SAMPLE_RATE.toDouble(),
            channels = 1u,
            interleaved = true,
        )
    }

    fun acquire(): AVAudioEngine? {
        lock.lock()
        try {
            val existing = engineOrNull
            if (existing != null) {
                users++
                return existing
            }
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionModeVoiceChat,
                AVAudioSessionCategoryOptionDefaultToSpeaker,
                null,
            )
            session.setPreferredSampleRate(CallAudioFormat.SAMPLE_RATE.toDouble(), null)
            session.setPreferredIOBufferDuration(0.01, null)
            if (!session.setActive(true, null)) return null
            val engine = AVAudioEngine()
            // Before start, and before anything reads the input format: enabling
            // voice processing changes the input node's format.
            engine.inputNode.setVoiceProcessingEnabled(true, null)
            engine.mainMixerNode // touching it connects mixer → output
            engine.prepare()
            if (!engine.startAndReturnError(null)) {
                session.setActive(false, null)
                return null
            }
            engineOrNull = engine
            users = 1
            return engine
        } finally {
            lock.unlock()
        }
    }

    fun release() {
        lock.lock()
        try {
            if (users == 0) return
            users--
            if (users > 0) return
            engineOrNull?.stop()
            engineOrNull = null
            AVAudioSession.sharedInstance().setActive(false, null)
        } finally {
            lock.unlock()
        }
    }
}

private class IosMicrophoneSource : AudioSource {
    private val lock = NSLock()
    private var engine: AVAudioEngine? = null
    private val pending = ShortArray(CallAudioFormat.FRAME_SAMPLES)
    private var filled = 0

    override fun start(onFrame: (ShortArray) -> Unit) {
        lock.lock()
        try {
            if (engine != null) return
            val e = IosCallAudioEngine.acquire() ?: return
            engine = e
            filled = 0
            val input = e.inputNode
            val inFormat = input.outputFormatForBus(0uL)
            val converter = AVAudioConverter(fromFormat = inFormat, toFormat = IosCallAudioEngine.callFormat)
            input.installTapOnBus(0uL, 1024u, inFormat) { buffer, _ ->
                if (buffer != null) convertAndCut(converter, inFormat.sampleRate, buffer, onFrame)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun convertAndCut(converter: AVAudioConverter, inRate: Double, buffer: AVAudioPCMBuffer, onFrame: (ShortArray) -> Unit) {
        val capacity = (buffer.frameLength.toDouble() * CallAudioFormat.SAMPLE_RATE / inRate).toInt() + 64
        val out = AVAudioPCMBuffer(IosCallAudioEngine.callFormat, capacity.toUInt())
        var supplied = false
        converter.convertToBuffer(out, null) { _, status ->
            if (supplied) {
                status?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                null
            } else {
                supplied = true
                status?.pointed?.value = AVAudioConverterInputStatus_HaveData
                buffer
            }
        }
        val samples = out.int16ChannelData?.get(0) ?: return
        val n = out.frameLength.toInt()
        var i = 0
        while (i < n) {
            val take = minOf(n - i, pending.size - filled)
            for (k in 0 until take) pending[filled + k] = samples[i + k]
            filled += take
            i += take
            if (filled == pending.size) {
                onFrame(pending.copyOf())
                filled = 0
            }
        }
    }

    override fun stop() {
        lock.lock()
        try {
            val e = engine ?: return
            engine = null
            e.inputNode.removeTapOnBus(0uL)
            IosCallAudioEngine.release()
        } finally {
            lock.unlock()
        }
    }
}

private class IosSpeakerSink : AudioSink {
    private class Stream(val player: AVAudioPlayerNode) {
        /** Buffers scheduled and not yet played: bounds latency when a stream bursts. */
        val queued = AtomicInt(0)
    }

    private val lock = NSLock()
    private var engine: AVAudioEngine? = null
    private var failed = false
    private val streams = mutableMapOf<String, Stream>()

    override fun play(publisher: String, pcm: ShortArray) {
        lock.lock()
        try {
            val e = engine ?: (if (failed) null else IosCallAudioEngine.acquire()).also {
                engine = it
                failed = it == null
            } ?: return
            val stream = streams.getOrPut(publisher) {
                val player = AVAudioPlayerNode()
                e.attachNode(player)
                e.connect(player, e.mainMixerNode, IosCallAudioEngine.playFormat)
                player.play()
                Stream(player)
            }
            // More than MAX_QUEUED frames behind: drop this one rather than grow the delay.
            if (stream.queued.value >= MAX_QUEUED) return
            val buf = AVAudioPCMBuffer(IosCallAudioEngine.playFormat, pcm.size.toUInt())
            buf.frameLength = pcm.size.toUInt()
            val dst = buf.floatChannelData?.get(0) ?: return
            for (i in pcm.indices) dst[i] = pcm[i] / 32768f
            stream.queued.incrementAndGet()
            stream.player.scheduleBuffer(buf) { stream.queued.decrementAndGet() }
        } finally {
            lock.unlock()
        }
    }

    override fun remove(publisher: String) {
        lock.lock()
        try {
            val s = streams.remove(publisher) ?: return
            s.player.stop()
            engine?.detachNode(s.player)
        } finally {
            lock.unlock()
        }
    }

    override fun stop() {
        lock.lock()
        try {
            val e = engine
            streams.values.forEach { s ->
                s.player.stop()
                e?.detachNode(s.player)
            }
            streams.clear()
            if (e != null) {
                engine = null
                IosCallAudioEngine.release()
            }
            failed = false
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** 10 × 20 ms: at most 200 ms queued per member. */
        const val MAX_QUEUED = 10
    }
}
