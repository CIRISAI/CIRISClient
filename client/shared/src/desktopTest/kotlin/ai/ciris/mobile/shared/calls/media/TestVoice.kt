package ai.ciris.mobile.shared.calls.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A speech-like test signal: no recording is needed, and it is the same on every run. */
internal object TestVoice {
    private const val SAMPLE_RATE = CallAudioFormat.SAMPLE_RATE

    /** Syllables of a voice: 0.15-0.35 s of sound, 0.05-0.3 s of pause, at about [level] dBFS. */
    fun voice(seconds: Double, seed: Int, pitchHz: Double, level: Double = -20.0): DoubleArray {
        val rnd = Random(seed)
        val out = DoubleArray((seconds * SAMPLE_RATE).toInt())
        var t = 0
        var phase = 0.0
        // Two formant resonators whose centres move per syllable.
        var y1 = 0.0; var y2 = 0.0; var z1 = 0.0; var z2 = 0.0
        while (t < out.size) {
            val on = (rnd.nextDouble(0.15, 0.35) * SAMPLE_RATE).toInt()
            val off = (rnd.nextDouble(0.05, 0.3) * SAMPLE_RATE).toInt()
            val f1 = rnd.nextDouble(300.0, 900.0)
            val f2 = rnd.nextDouble(900.0, 2500.0)
            val pitch = pitchHz * rnd.nextDouble(0.85, 1.2)
            val r = 0.97
            val a1 = 2 * r * cos(2 * PI * f1 / SAMPLE_RATE); val a2 = -r * r
            val b1 = 2 * r * cos(2 * PI * f2 / SAMPLE_RATE); val b2 = -r * r
            for (i in 0 until on) {
                if (t >= out.size) break
                phase += pitch / SAMPLE_RATE
                val pulse = if (phase >= 1) { phase -= 1; 1.0 } else 0.0
                val x = pulse + 0.05 * rnd.nextDouble(-1.0, 1.0)
                val v1 = x + a1 * y1 + a2 * y2; y2 = y1; y1 = v1
                val v2 = x + b1 * z1 + b2 * z2; z2 = z1; z1 = v2
                val env = sin(PI * i / on)
                out[t++] = (v1 * 0.6 + v2 * 0.4) * env
            }
            repeat(off) { if (t < out.size) out[t++] = 0.0 }
        }
        // Scale the active parts to the level asked for.
        val active = out.filter { abs(it) > 1e-9 }
        val rms = sqrt(active.sumOf { it * it } / active.size.coerceAtLeast(1))
        val target = 32768 * Math.pow(10.0, level / 20)
        for (i in out.indices) out[i] = out[i] / rms * target
        return out
    }
}
