package ai.ciris.mobile.shared.calls.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop echo canceller against a simulated room. The far end is a
 * speech-like signal (a pitched pulse train and breath noise through moving
 * formants, in syllables with pauses), it reaches the microphone through a
 * speaker buffer, a room of reflections and a microphone buffer, and the calls
 * come in the order and the bursts the real audio threads make them.
 *
 * ERLE (echo return loss enhancement) is the measure every canceller is judged
 * by: how much quieter the echo is after the canceller than before it.
 */
class EchoCancellerTest {
    private val rate = CallAudioFormat.SAMPLE_RATE
    private val frame = CallAudioFormat.FRAME_SAMPLES

    @Test fun theFftIsTheDft() {
        for (n in intArrayOf(960, 1920, 60, 30, 8)) {
            val rnd = Random(n)
            val re = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
            val im = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
            val oRe = DoubleArray(n)
            val oIm = DoubleArray(n)
            val fft = Fft(n)
            fft.forward(re, im, oRe, oIm)
            for (k in intArrayOf(0, 1, n / 3, n / 2, n - 1)) {
                var sr = 0.0
                var si = 0.0
                for (t in 0 until n) {
                    val a = -2 * PI * k * t / n
                    sr += re[t] * cos(a) - im[t] * sin(a)
                    si += re[t] * sin(a) + im[t] * cos(a)
                }
                assertEquals(sr, oRe[k], 1e-9 * n, "n=$n bin $k real")
                assertEquals(si, oIm[k], 1e-9 * n, "n=$n bin $k imaginary")
            }
            val bRe = DoubleArray(n)
            val bIm = DoubleArray(n)
            fft.inverse(oRe, oIm, bRe, bIm)
            for (t in 0 until n) assertEquals(re[t], bRe[t], 1e-9, "n=$n inverse at $t")
        }
    }

    private fun voice(seconds: Double, seed: Int, pitchHz: Double, level: Double = -20.0) =
        TestVoice.voice(seconds, seed, pitchHz, level)

    /** A room: the direct path at [gain] after [delayMs], then forty reflections decaying over 100 ms. */
    private class Room(rate: Int, delayMs: Int, gain: Double, seed: Int) {
        val taps: IntArray
        val gains: DoubleArray

        init {
            val rnd = Random(seed)
            val d = delayMs * rate / 1000
            val t = ArrayList<Int>()
            val g = ArrayList<Double>()
            t += d; g += gain
            repeat(40) {
                val after = rnd.nextInt(rate / 1000, rate / 10)
                t += d + after
                g += gain * 0.5 * exp(-after / (0.03 * rate)) * (if (rnd.nextBoolean()) 1 else -1)
            }
            taps = t.toIntArray()
            gains = g.toDoubleArray()
        }

        fun apply(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            for (j in taps.indices) {
                val d = taps[j]
                val g = gains[j]
                for (i in d until x.size) y[i] += g * x[i - d]
            }
            return y
        }
    }

    private class Run(val input: DoubleArray, val output: DoubleArray, val aec: EchoCanceller)

    /**
     * Drives the canceller the way the two audio threads do. The speaker sink writes
     * 20 ms frames, sometimes two at once and then none (a writer that woke late);
     * the microphone delivers 20 ms frames. [mic] is what the microphone hears, by
     * sample time; [far] is what the sink was handed, by sample time. The speaker
     * plays a sample [playMs] after it is handed over; the microphone delivers a
     * sample [captureMs] after it was heard.
     */
    private fun drive(far: DoubleArray, mic: DoubleArray, captureMs: Int, aec: EchoCanceller = EchoCanceller(), seed: Int = 1): Run {
        val rnd = Random(seed)
        val capture = captureMs * rate / 1000
        val out = DoubleArray(mic.size)
        val frames = far.size / frame
        var written = 0
        for (s in 0 until frames) {
            // The writer: usually one frame per 20 ms; one time in eight it is late, then catches up.
            val due = s + 1
            val burst = rnd.nextInt(8) == 0
            if (!burst) {
                while (written < due) {
                    aec.farEnd(ShortArray(frame) { far[written * frame + it].toInt().coerceIn(-32768, 32767).toShort() })
                    written++
                }
            }
            // The microphone frame that has been captured by now.
            val micFrame = s - capture / frame
            if (micFrame >= 0) {
                val o = micFrame * frame
                val pcm = ShortArray(frame) { mic[o + it].toInt().coerceIn(-32768, 32767).toShort() }
                val cleaned = aec.capture(pcm)
                for (i in 0 until frame) out[o + i] = cleaned[i].toDouble()
            }
        }
        return Run(mic, out, aec)
    }

    private fun db(num: Double, den: Double) = 10 * log10(num / den)
    private fun energy(x: DoubleArray, from: Double, to: Double, shift: Int = 0): Double {
        var s = 0.0
        val a = (from * rate).toInt()
        val b = minOf((to * rate).toInt(), x.size - shift)
        for (i in a until b) s += x[i + shift] * x[i + shift]
        return s
    }

    /** The output is 10 ms behind the input. */
    private val lag = AEC_BLOCK

    private fun speaker(far: DoubleArray, playMs: Int): DoubleArray {
        val d = playMs * rate / 1000
        return DoubleArray(far.size) { if (it >= d) far[it - d] else 0.0 }
    }

    private fun noise(size: Int, dbfs: Double, seed: Int): DoubleArray {
        val rnd = Random(seed)
        val a = 32768 * Math.pow(10.0, dbfs / 20) * sqrt(3.0)
        return DoubleArray(size) { rnd.nextDouble(-a, a) }
    }

    @Test fun anEchoAloneIsTakenOut() {
        val far = voice(12.0, seed = 11, pitchHz = 120.0)
        val room = Room(rate, delayMs = 3, gain = 0.5, seed = 5)
        val echo = room.apply(speaker(far, playMs = 120))
        val floor = noise(far.size, -70.0, 9)
        val mic = DoubleArray(far.size) { echo[it] + floor[it] }
        val run = drive(far, mic, captureMs = 40)
        val before = energy(mic, 6.0, 12.0)
        val after = energy(run.output, 6.0, 12.0, shift = lag)
        val erle = db(before, after)
        val delay = run.aec.measuredDelayMs
        val early = db(energy(mic, 2.5, 4.0), energy(run.output, 2.5, 4.0, lag))
        val facts = "ERLE ${"%.1f".format(erle)} dB over 6-12 s; 2.5-4 s ${"%.1f".format(early)} dB; first 2 s ${"%.1f".format(db(energy(mic, 0.5, 2.0), energy(run.output, 0.5, 2.0, lag)))} dB; " +
            "measured delay $delay ms (true 163); leak ${"%.4f".format(run.aec.leak)}"
        println("[aec] echo alone: $facts")
        assertNotNull(delay, "no delay was measured. $facts")
        assertTrue(abs(delay - 163) <= 20, "the delay is found within 20 ms. $facts")
        // Measured 43.0 dB (6-12 s) when this was written. 35 leaves room for the
        // signal generator, not for a canceller that got worse.
        assertTrue(erle >= 35.0, "an echo alone is at least 35 dB quieter after 6 s. $facts")
        assertTrue(early >= 20.0, "and at least 20 dB quieter by the third second of the call. $facts")
    }

    @Test fun theNearEndTalkingThroughTheEchoIsKept() {
        // 0-6 s the far end alone (the filter learns), 6-10 s both talk, 10-12 s the near end alone.
        val far = voice(12.0, seed = 21, pitchHz = 110.0).also { for (i in (10 * rate) until it.size) it[i] = 0.0 }
        val near = voice(12.0, seed = 22, pitchHz = 210.0, level = -26.0).also { for (i in 0 until 6 * rate) it[i] = 0.0 }
        val room = Room(rate, delayMs = 3, gain = 0.5, seed = 6)
        val echo = room.apply(speaker(far, playMs = 120))
        val floor = noise(far.size, -70.0, 10)
        val mic = DoubleArray(far.size) { echo[it] + near[it] + floor[it] }
        val run = drive(far, mic, captureMs = 40)
        // How much of the near end survives while both talk: the output's projection on it.
        var dot = 0.0
        var nn = 0.0
        var oo = 0.0
        for (i in 7 * rate until 10 * rate) {
            val o = run.output[i + lag]
            dot += o * near[i]; nn += near[i] * near[i]; oo += o * o
        }
        val kept = dot / nn
        val corr = dot / sqrt(nn * oo)
        // And alone, after the far end stops: the output is the input, 10 ms later.
        var diff = 0.0
        var inE = 0.0
        for (i in (10.5 * rate).toInt() until 12 * rate - lag) {
            val d = run.output[i + lag] - mic[i]
            diff += d * d; inE += mic[i] * mic[i]
        }
        val echoInDoubleTalk = db(energy(echo, 7.0, 10.0), energy(run.output, 7.0, 10.0, lag) - kept * kept * nn)
        val facts = "double talk: near end kept at ${"%.2f".format(kept)} of its amplitude, correlation ${"%.3f".format(corr)}; " +
            "near end alone: ${"%.1f".format(db(inE, diff))} dB from the input; echo ${"%.1f".format(echoInDoubleTalk)} dB down in double talk"
        println("[aec] double talk: $facts")
        assertTrue(kept in 0.7..1.1, "the near end keeps at least 70% of its amplitude while both talk. $facts")
        assertTrue(corr >= 0.85, "what is sent while both talk is mostly the near end. $facts")
        assertTrue(db(inE, diff) >= 30.0, "with no far end the near end passes unchanged. $facts")
    }

    @Test fun aMovedSpeakerIsLearnedAgain() {
        val far = voice(16.0, seed = 31, pitchHz = 130.0)
        val a = Room(rate, delayMs = 3, gain = 0.5, seed = 7).apply(speaker(far, playMs = 120))
        val b = Room(rate, delayMs = 8, gain = 0.4, seed = 8).apply(speaker(far, playMs = 120))
        val floor = noise(far.size, -70.0, 11)
        val mic = DoubleArray(far.size) { (if (it < 8 * rate) a[it] else b[it]) + floor[it] }
        val run = drive(far, mic, captureMs = 40)
        val before = db(energy(mic, 5.0, 8.0), energy(run.output, 5.0, 8.0, lag))
        val after = db(energy(mic, 13.0, 16.0), energy(run.output, 13.0, 16.0, lag))
        val facts = "ERLE 5-8 s ${"%.1f".format(before)} dB; path moved at 8 s; 13-16 s ${"%.1f".format(after)} dB"
        println("[aec] moved: $facts")
        assertTrue(after >= 25.0, "five seconds after the speaker moves the echo is 25 dB down again. $facts")
    }

    @Test fun aLongerDelayIsFound() {
        val far = voice(12.0, seed = 41, pitchHz = 140.0)
        val echo = Room(rate, delayMs = 3, gain = 0.5, seed = 9).apply(speaker(far, playMs = 300))
        val floor = noise(far.size, -70.0, 12)
        val mic = DoubleArray(far.size) { echo[it] + floor[it] }
        val run = drive(far, mic, captureMs = 60)
        val erle = db(energy(mic, 6.0, 12.0), energy(run.output, 6.0, 12.0, lag))
        val delay = run.aec.measuredDelayMs
        val facts = "measured $delay ms (true 363); ERLE 6-12 s ${"%.1f".format(erle)} dB"
        println("[aec] long delay: $facts")
        assertNotNull(delay, facts)
        assertTrue(abs(delay - 363) <= 20, "a 363 ms delay is found within 20 ms. $facts")
        assertTrue(erle >= 30.0, "and the echo is 30 dB down (35.3 measured). $facts")
    }

    @Test fun silenceStaysSilentAndNothingBlowsUp() {
        val aec = EchoCanceller()
        repeat(500) {
            aec.farEnd(ShortArray(frame))
            val out = aec.capture(ShortArray(frame))
            assertTrue(out.all { it.toInt() == 0 }, "silence in, silence out")
        }
        // Full-scale far end with a microphone that hears none of it: nothing is invented.
        val rnd = Random(3)
        var outE = 0.0
        repeat(300) {
            aec.farEnd(ShortArray(frame) { rnd.nextInt(-32768, 32767).toShort() })
            val out = aec.capture(ShortArray(frame))
            outE += out.sumOf { it.toDouble() * it }
        }
        assertEquals(0.0, outE, "a microphone that hears nothing sends nothing, however loud the far end")
    }
}
