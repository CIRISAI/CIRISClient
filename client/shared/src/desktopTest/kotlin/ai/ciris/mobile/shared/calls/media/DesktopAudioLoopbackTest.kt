package ai.ciris.mobile.shared.calls.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Desktop call audio through a virtual microphone: the speaker sink plays a
 * 440 Hz tone through Opus, the microphone source hears it back, and the pitch
 * survives. Runs only with CIRIS_AUDIO_LOOPBACK=1, where PULSE_SINK and
 * PULSE_SOURCE point at a PulseAudio null sink and its monitor (the
 * five-platform runner's virtual device; `packaging/virtual_audio.sh`). Without
 * it there may be no audio device at all, and silence is not a test.
 */
class DesktopAudioLoopbackTest {
    @Test fun pcmRoundTripsThroughLittleEndianBytes() {
        val pcm = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1234)
        assertTrue(pcm.contentEquals(pcmOf(bytesOf(pcm))))
    }

    @Test fun aToneGoesOutTheSpeakerAndBackInTheMicrophone() {
        if (System.getenv("CIRIS_AUDIO_LOOPBACK") != "1") return
        assertTrue(desktopMicrophoneAvailable() && desktopSpeakerAvailable(), "no audio line: is the virtual device up?")
        val tone = ToneSource(kotlinx.coroutines.GlobalScope)
        val enc = createOpusEncoder()
        val dec = createOpusDecoder()
        val sink = DesktopSpeakerSink()
        val mic = DesktopMicrophoneSource()
        val heard = java.util.concurrent.ConcurrentLinkedQueue<Short>()
        mic.start { pcm -> pcm.forEach { heard += it } }
        try {
            // Two seconds of tone through Opus both ways, paced by an absolute clock so
            // the loop does not drift slower than real time and starve the line.
            val t0 = System.nanoTime()
            repeat(100) { i ->
                enc.encode(tone.nextFrame()) { p -> dec.decode(p) { pcm -> sink.play("ada", pcm) } }
                val due = t0 + (i + 1) * CallAudioFormat.FRAME_MS * 1_000_000L
                val wait = (due - System.nanoTime()) / 1_000_000L
                if (wait > 0) Thread.sleep(wait)
            }
            Thread.sleep(300)
        } finally {
            mic.stop()
            sink.stop()
        }
        val samples = heard.toShortArray()
        assertTrue(samples.size > CallAudioFormat.SAMPLE_RATE, "heard ${samples.size} samples")
        // Pitch per loud 20 ms block, median of them: a drained line plays silence,
        // and counting crossings across silence would understate the pitch.
        val n = CallAudioFormat.FRAME_SAMPLES
        val pitches = (0 until samples.size / n).mapNotNull { b ->
            val block = samples.copyOfRange(b * n, (b + 1) * n)
            if (block.map { abs(it.toInt()) }.average() < 1500) return@mapNotNull null
            var c = 0
            for (i in 1 until block.size) if ((block[i - 1] < 0) != (block[i] < 0)) c++
            c / 2.0 / (n.toDouble() / CallAudioFormat.SAMPLE_RATE)
        }.sorted()
        assertTrue(pitches.size >= 25, "the tone was heard for at least half a second: ${pitches.size} loud blocks")
        val hz = pitches[pitches.size / 2]
        assertTrue(abs(hz - 440) < 30, "heard $hz Hz through the virtual microphone, played 440 Hz")
    }
}
