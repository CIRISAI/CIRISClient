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
        val t0mic = System.nanoTime()
        val arrivals = java.util.concurrent.ConcurrentLinkedQueue<Long>() // ms since start, per frame
        mic.start { pcm -> arrivals += (System.nanoTime() - t0mic) / 1_000_000; pcm.forEach { heard += it } }
        try {
            // A cold sound server can take seconds to deliver the first captured frame
            // (2 s on a CI runner): wait for the microphone before playing anything.
            val warm = System.nanoTime() + 10_000_000_000L
            while (arrivals.isEmpty() && System.nanoTime() < warm) Thread.sleep(50)
            assertTrue(arrivals.isNotEmpty(), "the microphone delivered nothing in 10 s")
            heard.clear()
            // Three seconds of tone through Opus both ways, paced by an absolute clock so
            // the loop does not drift slower than real time and starve the line.
            val t0 = System.nanoTime()
            repeat(150) { i ->
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
        // Where capture stopped, if it did: frames that arrived in each half second.
        val perHalfSecond = arrivals.groupingBy { it / 500 }.eachCount().toSortedMap()
        assertTrue(samples.size > CallAudioFormat.SAMPLE_RATE, "heard ${samples.size} samples; frames per 500 ms: $perHalfSecond")
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

    /**
     * The echo canceller on real lines: the virtual microphone hears exactly what the
     * speaker plays, a few buffers later, so what the call would send back is all
     * echo. With the canceller between them it should be mostly gone. The canceller
     * runs on the raw frames here (the source is opened without it), so the test
     * sees both what the microphone heard and what would be sent.
     */
    @Test fun theSpeakerIsNotSentBackThroughTheMicrophone() {
        if (System.getenv("CIRIS_AUDIO_LOOPBACK") != "1") return
        assertTrue(desktopMicrophoneAvailable() && desktopSpeakerAvailable(), "no audio line: is the virtual device up?")
        val echo = EchoCanceller()
        val sink = DesktopSpeakerSink(echo)
        val mic = DesktopMicrophoneSource()
        val heard = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, Pair<Double, Double>>>() // ms, (raw, sent) energy
        val t0 = System.nanoTime()
        mic.start { raw ->
            val sent = echo.capture(raw)
            heard += ((System.nanoTime() - t0) / 1_000_000) to (raw.sumOf { it.toDouble() * it } to sent.sumOf { it.toDouble() * it })
        }
        val voice = TestVoice.voice(10.0, seed = 7, pitchHz = 150.0, level = -12.0)
        try {
            val warm = System.nanoTime() + 10_000_000_000L
            while (heard.isEmpty() && System.nanoTime() < warm) Thread.sleep(50)
            assertTrue(heard.isNotEmpty(), "the microphone delivered nothing in 10 s")
            val start = System.nanoTime()
            val frames = voice.size / CallAudioFormat.FRAME_SAMPLES
            for (i in 0 until frames) {
                val o = i * CallAudioFormat.FRAME_SAMPLES
                sink.play("ada", ShortArray(CallAudioFormat.FRAME_SAMPLES) { voice[o + it].toInt().coerceIn(-32768, 32767).toShort() })
                val due = start + (i + 1) * CallAudioFormat.FRAME_MS * 1_000_000L
                val wait = (due - System.nanoTime()) / 1_000_000L
                if (wait > 0) Thread.sleep(wait)
            }
            Thread.sleep(300)
        } finally {
            mic.stop()
            sink.stop()
        }
        val playedFrom = heard.first().first
        // The last five seconds of the ten, once the canceller has had time to learn.
        val late = heard.filter { it.first > playedFrom + 5_500 && it.first < playedFrom + 10_000 }
        val raw = late.sumOf { it.second.first }
        val sent = late.sumOf { it.second.second }
        val erle = 10 * kotlin.math.log10(raw / sent.coerceAtLeast(1.0))
        val facts = "${late.size} frames; heard ${"%.1f".format(10 * kotlin.math.log10(raw / late.size.coerceAtLeast(1) / 960 / 32768.0 / 32768.0))} dBFS; " +
            "${"%.1f".format(erle)} dB less sent than heard; delay measured ${echo.measuredDelayMs} ms"
        println("[aec loopback] $facts")
        assertTrue(late.size > 100, "the microphone kept delivering. $facts")
        assertTrue(raw > 0.0, "the microphone heard the speaker. $facts")
        assertTrue(erle >= 25.0, "what would be sent is at least 25 dB quieter than the echo heard. $facts")
    }
}
