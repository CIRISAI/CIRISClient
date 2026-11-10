package ai.ciris.mobile.shared.calls.media

import kotlin.math.abs

/**
 * This device's call hardware (CSD-134). Null where the platform has none, or
 * where it is not built yet; the screen then says which, never pretends.
 */
interface CallMediaDevices {
    fun microphone(): AudioSource?
    fun speaker(): AudioSink?
    fun camera(): VideoSource?
}

/** The platform's devices: javax.sound on desktop, AudioRecord/AudioTrack on Android. */
expect fun platformCallMediaDevices(): CallMediaDevices

/**
 * What a call captures from in the five-platform runner: CI machines have no
 * camera and mostly no microphone, so test mode sends a 440 Hz tone and a moving
 * test pattern, the same on every platform. Playback stays the real speaker, so
 * on Linux it goes to the runner's virtual device.
 */
class TestModeDevices(private val real: CallMediaDevices, private val scope: kotlinx.coroutines.CoroutineScope) : CallMediaDevices {
    override fun microphone(): AudioSource = ToneSource(scope)
    override fun speaker(): AudioSink? = real.speaker()
    override fun camera(): VideoSource = TestPatternSource(scope)
}

/**
 * Wraps a sink and keeps, per publisher, what arrived: frames heard and the pitch
 * of the latest loud frame. The runner reads these from the screen's test tags,
 * so "the call works" is a measurement, not a connection.
 */
class MeasuringSink(private val inner: AudioSink?) : AudioSink {
    class Heard(val frames: Int, val hz: Double?)

    private val heard = mutableMapOf<String, Heard>()

    fun heard(publisher: String): Heard? = heard[publisher]

    override fun play(publisher: String, pcm: ShortArray) {
        val prior = heard[publisher]
        heard[publisher] = Heard((prior?.frames ?: 0) + 1, pitchOf(pcm) ?: prior?.hz)
        inner?.play(publisher, pcm)
    }

    override fun remove(publisher: String) {
        heard.remove(publisher)
        inner?.remove(publisher)
    }

    override fun stop() {
        heard.clear()
        inner?.stop()
    }

    companion object {
        /** Zero-crossing pitch of one loud frame; null for a quiet one, which carries no pitch. */
        fun pitchOf(pcm: ShortArray): Double? {
            if (pcm.isEmpty() || pcm.map { abs(it.toInt()) }.average() < 1000) return null
            var c = 0
            for (i in 1 until pcm.size) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) c++
            return c / 2.0 / (pcm.size.toDouble() / CallAudioFormat.SAMPLE_RATE)
        }
    }
}
