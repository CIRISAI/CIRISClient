package ai.ciris.mobile.shared.calls.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Acoustic echo cancellation for the desktop, where Java Sound has none.
 *
 * Android (VOICE_COMMUNICATION + AcousticEchoCanceler), iOS (voice processing
 * I/O) and the browser (getUserMedia echoCancellation) use the platform's. A
 * desktop call without one sends the other people their own voices back a
 * quarter of a second late, which is the first thing anybody notices.
 *
 * Nothing here is new. It is the structure every open-source canceller has:
 *
 *  - [Mdf], a partitioned-block frequency-domain adaptive filter (the
 *    "multidelay block frequency domain" filter, Soo & Pang 1990) whose step
 *    size follows Valin's double-talk-robust rule ("On adjusting the learning
 *    rate in frequency domain echo cancellation with double-talk", IEEE TASLP
 *    2007). SpeexDSP's `mdf.c` (which Mumble uses) is this filter; its
 *    constants are the published ones and are kept here.
 *  - [ResidualEchoSuppressor]: what the linear filter leaves (loudspeaker
 *    distortion, a path that moved) is removed per frequency bin with a gain
 *    drawn from the filter's own leak estimate, as Speex's preprocessor and
 *    WebRTC's suppressor do.
 *  - [EchoDelayEstimator]: Java Sound will not say how long the speaker and
 *    microphone buffers are, so the far-end reference is aligned by finding the
 *    lag at which the speaker's energy envelope best predicts the microphone's
 *    (WebRTC's delay estimator does the same with binary spectra).
 *
 * The audio is mono 48 kHz in 10 ms blocks of 480 samples, and the output is
 * 10 ms later than the input (the suppressor's overlap-add).
 */

/** Samples per block: 10 ms at the call's 48 kHz. */
internal const val AEC_BLOCK = 480

/**
 * The adaptive filter: given the microphone block and the far-end block that is
 * aligned with it, returns the microphone with the echo estimate taken out.
 * Samples are in 16-bit units (±32768) held as doubles.
 */
internal class Mdf(
    private val n: Int = AEC_BLOCK,
    private val partitions: Int = 25,
    /** Partitions given the time constraint each block, the newest always among them. */
    private val constrained: Int = 4,
) {
    private val size = 2 * n
    private val fft = Fft(size)

    // Far-end spectra, newest first: X[0] is the transform of [previous block, this block].
    private val xRe = Array(partitions) { DoubleArray(size) }
    private val xIm = Array(partitions) { DoubleArray(size) }
    private val wRe = Array(partitions) { DoubleArray(size) }
    private val wIm = Array(partitions) { DoubleArray(size) }

    private val farPrev = DoubleArray(n)
    private val power = DoubleArray(size)
    private val eh = DoubleArray(size)
    private val yh = DoubleArray(size)

    private val bufRe = DoubleArray(size)
    private val bufIm = DoubleArray(size)
    private val zeroIm = DoubleArray(size)
    private val yRe = DoubleArray(size)
    private val yIm = DoubleArray(size)
    private val eRe = DoubleArray(size)
    private val eIm = DoubleArray(size)
    private val yzRe = DoubleArray(size)
    private val yzIm = DoubleArray(size)
    private val mu = DoubleArray(size)

    /**
     * Each partition's share of the step (they sum to a little under 1): larger
     * where the filter already holds more of the echo, the "proportionate"
     * adaptation Speex uses. Without it every partition takes the whole step and
     * the filter diverges.
     */
    private val prop = DoubleArray(partitions)

    private var pey = 1.0
    private var pyy = 1.0
    private var sumAdapt = 0.0
    private var adapted = false
    private var count = 0L
    private var screwedUp = 0

    /** How much of the echo estimate's power is still in the output (0..1): Valin's leak estimate. */
    var leak = 1.0
        private set

    /** The echo estimate's power per bin of the last block, for the suppressor: |FFT([0, y])|². */
    val echoPower = DoubleArray(size)

    fun reset() {
        for (m in 0 until partitions) {
            xRe[m].fill(0.0); xIm[m].fill(0.0); wRe[m].fill(0.0); wIm[m].fill(0.0)
        }
        farPrev.fill(0.0); power.fill(0.0); eh.fill(0.0); yh.fill(0.0); echoPower.fill(0.0)
        pey = 1.0; pyy = 1.0; sumAdapt = 0.0; adapted = false; leak = 1.0; screwedUp = 0
        initialProp()
    }

    init { initialProp() }

    /** An exponentially decaying share before anything is learned: echoes are strongest early. */
    private fun initialProp() {
        val decay = exp(-2.4 / partitions)
        prop[0] = 0.7
        var sum = prop[0]
        for (m in 1 until partitions) { prop[m] = prop[m - 1] * decay; sum += prop[m] }
        for (m in 0 until partitions) prop[m] = 0.8 * prop[m] / sum
    }

    /** Share the step by each partition's weight norm, with a tenth of the largest as a floor. */
    private fun adjustProp() {
        var maxSum = 1.0
        for (m in 0 until partitions) {
            var t = 1.0
            val r = wRe[m]; val i = wIm[m]
            for (k in 0 until size) t += r[k] * r[k] + i[k] * i[k]
            prop[m] = sqrt(t)
            if (prop[m] > maxSum) maxSum = prop[m]
        }
        var sum = 0.0
        for (m in 0 until partitions) { prop[m] += 0.1 * maxSum; sum += prop[m] }
        for (m in 0 until partitions) prop[m] = 0.99 * prop[m] / sum
    }

    /**
     * The reference moved by whole blocks: the echo now sits [k] partitions
     * earlier in the filter (later when negative). What was learned moves with it
     * instead of being thrown away. [past] gives the far-end block `j` blocks
     * before the next one [process] will be given, at the new alignment, so the
     * far-end history is rebuilt to match.
     */
    fun shift(k: Int, past: (j: Int) -> DoubleArray) {
        if (k == 0) return
        if (abs(k) >= partitions) { reset(); return }
        if (k > 0) {
            for (m in 0 until partitions) {
                if (m + k < partitions) { wRe[m + k].copyInto(wRe[m]); wIm[m + k].copyInto(wIm[m]) } else { wRe[m].fill(0.0); wIm[m].fill(0.0) }
            }
        } else {
            for (m in partitions - 1 downTo 0) {
                if (m + k >= 0) { wRe[m + k].copyInto(wRe[m]); wIm[m + k].copyInto(wIm[m]) } else { wRe[m].fill(0.0); wIm[m].fill(0.0) }
            }
        }
        // X[m] is the window ending m + 1 blocks before the next block.
        for (m in 0 until partitions) {
            val cur = past(m + 1)
            val prev = past(m + 2)
            for (i in 0 until n) { bufRe[i] = prev[i]; bufRe[n + i] = cur[i] }
            fft.forward(bufRe, zeroIm, xRe[m], xIm[m])
        }
        past(1).copyInto(farPrev)
    }

    /** [mic] and [far] are [n] samples; [outE] gets the echo-cancelled microphone, [outY] the echo estimate. */
    fun process(mic: DoubleArray, far: DoubleArray, outE: DoubleArray, outY: DoubleArray) {
        count++
        // Age the far-end spectra and transform the newest window.
        val oldRe = xRe[partitions - 1]
        val oldIm = xIm[partitions - 1]
        for (m in partitions - 1 downTo 1) { xRe[m] = xRe[m - 1]; xIm[m] = xIm[m - 1] }
        xRe[0] = oldRe
        xIm[0] = oldIm
        for (i in 0 until n) { bufRe[i] = farPrev[i]; bufRe[n + i] = far[i] }
        fft.forward(bufRe, zeroIm, xRe[0], xIm[0])
        far.copyInto(farPrev)

        // Echo estimate: the sum over partitions, back to time, second half (overlap-save).
        yRe.fill(0.0); yIm.fill(0.0)
        for (m in 0 until partitions) {
            val ar = xRe[m]; val ai = xIm[m]; val br = wRe[m]; val bi = wIm[m]
            for (k in 0 until size) {
                yRe[k] += ar[k] * br[k] - ai[k] * bi[k]
                yIm[k] += ar[k] * bi[k] + ai[k] * br[k]
            }
        }
        fft.inverse(yRe, yIm, bufRe, bufIm)
        var sxx = 0.0; var sdd = 0.0; var see = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val y = bufRe[n + i]
            val e = mic[i] - y
            outY[i] = y
            outE[i] = e
            sxx += far[i] * far[i]; sdd += mic[i] * mic[i]; see += e * e; syy += y * y
        }

        // A filter that makes things worse than no filter, for half a second, starts over.
        if (!see.isFinite() || !syy.isFinite()) { reset(); mic.copyInto(outE); outY.fill(0.0); return }
        screwedUp = if (see > sdd + n * 10_000.0) screwedUp + 1 else 0
        if (screwedUp >= 50) { reset(); mic.copyInto(outE); outY.fill(0.0); return }

        // Error and echo estimate in the zero-padded form the gradient needs.
        for (i in 0 until n) { bufRe[i] = 0.0; bufRe[n + i] = outE[i] }
        fft.forward(bufRe, zeroIm, eRe, eIm)
        for (i in 0 until n) { bufRe[i] = 0.0; bufRe[n + i] = outY[i] }
        fft.forward(bufRe, zeroIm, yzRe, yzIm)

        // Far-end power per bin, smoothed: the step's normaliser.
        val ss = 0.35 / partitions
        for (k in 0 until size) {
            val xf = xRe[0][k] * xRe[0][k] + xIm[0][k] * xIm[0][k]
            power[k] = (1 - ss) * power[k] + 1 + ss * xf
        }

        // Valin's leak estimate: how the error's power moves with the estimate's.
        val specAverage = n.toDouble() / CallAudioFormat.SAMPLE_RATE
        var pey1 = 0.0
        var pyy1 = 0.0
        for (k in 0 until size) {
            val rf = eRe[k] * eRe[k] + eIm[k] * eIm[k]
            val yf = yzRe[k] * yzRe[k] + yzIm[k] * yzIm[k]
            echoPower[k] = yf
            eh[k] = (1 - specAverage) * eh[k] + specAverage * rf
            yh[k] = (1 - specAverage) * yh[k] + specAverage * yf
            pey1 += (rf - eh[k]) * (yf - yh[k])
            pyy1 += (yf - yh[k]) * (yf - yh[k])
        }
        pyy1 = sqrt(pyy1)
        pey1 /= (1 + pyy1)
        val beta0 = 2.0 * n / CallAudioFormat.SAMPLE_RATE
        val betaMax = 0.5 * n / CallAudioFormat.SAMPLE_RATE
        var a = beta0 * syy
        if (a > betaMax * see) a = betaMax * see
        val alpha = a / (see + 1)
        pey = (1 - alpha) * pey + alpha * pey1
        pyy = (1 - alpha) * pyy + alpha * pyy1
        if (pyy < 1) pyy = 1.0
        if (pey < MIN_LEAK * pyy) pey = MIN_LEAK * pyy
        if (pey > pyy) pey = pyy
        leak = pey / pyy

        // The step: residual echo over error, per bin (adapted), or a cautious fixed rate (not yet).
        var rer = (0.0001 * sxx + 3.0 * leak * syy) / (see + 1)
        if (rer > 0.5) rer = 0.5
        if (!adapted && sumAdapt > partitions && leak > 0.03) adapted = true
        if (adapted) {
            for (k in 0 until size) {
                var r = leak * (yzRe[k] * yzRe[k] + yzIm[k] * yzIm[k])
                val e = eRe[k] * eRe[k] + eIm[k] * eIm[k] + 1
                if (r > 0.5 * e) r = 0.5 * e
                r = 0.7 * r + 0.3 * rer * e
                mu[k] = r / (e * (power[k] + 10))
            }
        } else {
            var rate = 0.0
            if (sxx > n * 1000.0) {
                var t = 0.25 * sxx
                if (t > 0.25 * see) t = 0.25 * see
                rate = t / (see + 1)
            }
            for (k in 0 until size) mu[k] = rate / (power[k] + 10)
            sumAdapt += rate
        }

        // A silent far end teaches the filter nothing: leave it alone.
        if (sxx <= n * 100.0) return

        // Gradient into every partition, then the time constraint on two of them
        // (the newest, and one more in rotation): the "AUMDF" economy.
        adjustProp()
        for (m in 0 until partitions) {
            val ar = xRe[m]; val ai = xIm[m]; val br = wRe[m]; val bi = wIm[m]
            val share = prop[m]
            for (k in 0 until size) {
                val gr = (ar[k] * eRe[k] + ai[k] * eIm[k]) * mu[k] * share // conj(X)·E
                val gi = (ar[k] * eIm[k] - ai[k] * eRe[k]) * mu[k] * share
                br[k] += gr
                bi[k] += gi
            }
        }
        constrain(0)
        if (partitions > 1) {
            val others = minOf(constrained - 1, partitions - 1)
            for (j in 0 until others) constrain(1 + ((count * others + j) % (partitions - 1)).toInt())
        }
    }

    /** Keep partition [m]'s impulse response in its first [n] taps: overlap-save wraps the rest around. */
    private fun constrain(m: Int) {
        fft.inverse(wRe[m], wIm[m], bufRe, bufIm)
        for (i in n until size) { bufRe[i] = 0.0 }
        for (i in 0 until size) bufIm[i] = 0.0
        fft.forward(bufRe, bufIm, wRe[m], wIm[m])
    }

    private companion object {
        const val MIN_LEAK = 0.005
    }
}

/**
 * Removes what the linear filter left, per frequency bin, by a gain drawn from
 * the echo estimate and the filter's leak. Square-root Hann windows at 50%
 * overlap, so with every gain at 1 the output is the input, [n] samples later.
 */
internal class ResidualEchoSuppressor(private val n: Int = AEC_BLOCK) {
    private val size = 2 * n
    private val fft = Fft(size)
    private val window = DoubleArray(size) { sin(PI * (it + 0.5) / size) }
    private val ePrev = DoubleArray(n)
    private val yPrev = DoubleArray(n)
    private val dPrev = DoubleArray(n)
    private val dRe = DoubleArray(size)
    private val dIm = DoubleArray(size)
    private val tail = DoubleArray(n)
    private val gain = DoubleArray(size) { 1.0 }

    /**
     * Per bin, how much of the echo estimate's power is still in the output: the
     * minimum of error over estimate, tracked fast down and slowly up. Talking at
     * the near end only ever adds to the error, so the minimum is the echo's.
     */
    private val leftover = DoubleArray(size) { 1.0 }
    private val smoothE = DoubleArray(size)
    private val smoothY = DoubleArray(size)
    private val bufRe = DoubleArray(size)
    private val zeroIm = DoubleArray(size)
    private val eRe = DoubleArray(size)
    private val eIm = DoubleArray(size)
    private val yRe = DoubleArray(size)
    private val yIm = DoubleArray(size)
    private val outRe = DoubleArray(size)
    private val outIm = DoubleArray(size)
    private val timeIm = DoubleArray(size)

    fun reset() {
        ePrev.fill(0.0); yPrev.fill(0.0); dPrev.fill(0.0); tail.fill(0.0); gain.fill(1.0); leftover.fill(1.0); smoothE.fill(0.0); smoothY.fill(0.0)
    }

    /**
     * [e] the filter's output, [y] its echo estimate, [d] the microphone it was
     * taken from, all [n] samples. Writes [n] samples to [out].
     */
    fun process(e: DoubleArray, y: DoubleArray, d: DoubleArray, leak: Double, out: DoubleArray) {
        for (i in 0 until n) {
            bufRe[i] = ePrev[i] * window[i]; bufRe[n + i] = e[i] * window[n + i]
        }
        fft.forward(bufRe, zeroIm, eRe, eIm)
        for (i in 0 until n) {
            bufRe[i] = yPrev[i] * window[i]; bufRe[n + i] = y[i] * window[n + i]
        }
        fft.forward(bufRe, zeroIm, yRe, yIm)
        for (i in 0 until n) {
            bufRe[i] = dPrev[i] * window[i]; bufRe[n + i] = d[i] * window[n + i]
        }
        fft.forward(bufRe, zeroIm, dRe, dIm)
        e.copyInto(ePrev)
        y.copyInto(yPrev)
        d.copyInto(dPrev)

        // What is left of the echo is the larger of the filter's leak and this bin's
        // measured leftover, times the estimate's power. Take twice that out (the
        // over-subtraction every suppressor uses), never more than the floor, and let
        // the gain recover over a few blocks instead of at once.
        for (k in 0 until size) {
            val pe = eRe[k] * eRe[k] + eIm[k] * eIm[k]
            val py = yRe[k] * yRe[k] + yIm[k] * yIm[k]
            // A single block's ratio is too noisy to take a minimum of: its dips are
            // far below its mean. Smooth both powers first.
            smoothE[k] = 0.7 * smoothE[k] + 0.3 * pe
            smoothY[k] = 0.7 * smoothY[k] + 0.3 * py
            if (smoothY[k] > MIN_ECHO_POWER) {
                val ratio = smoothE[k] / smoothY[k]
                leftover[k] = if (ratio < leftover[k]) 0.7 * leftover[k] + 0.3 * ratio else leftover[k] * LEFTOVER_RISE
                if (leftover[k] > 1.0) leftover[k] = 1.0
                if (leftover[k] < 1e-5) leftover[k] = 1e-5
            }
            // The gain is decided on the smoothed powers too: decided per block, the
            // bins whose leftover happens to sit above twice its mean (one in seven)
            // keep most of it, and they carry about 40% of its energy.
            val residual = OVER_SUBTRACT * maxOf(leak, leftover[k])
            var g = if (smoothE[k] <= 0.0) 1.0 else 1.0 - residual * smoothY[k] / smoothE[k]
            // Never send a bin louder than the microphone heard it. In a pause the
            // microphone hears almost nothing, but the filter's small errors, applied
            // to the last quarter second of sound, still make an estimate: then
            // mic − estimate is louder than the mic, and it is noise the filter made,
            // which matches the estimate and so would pass as speech. (AEC3 makes the
            // same choice by block: it sends the input when that is quieter.)
            val pd = dRe[k] * dRe[k] + dIm[k] * dIm[k]
            if (pe > 0.0 && g * pe > pd) g = pd / pe
            if (g < FLOOR_POWER) g = FLOOR_POWER
            g = sqrt(g)
            gain[k] = if (g < gain[k]) g else RELEASE * gain[k] + (1 - RELEASE) * g
            outRe[k] = eRe[k] * gain[k]
            outIm[k] = eIm[k] * gain[k]
        }
        fft.inverse(outRe, outIm, bufRe, timeIm)
        for (i in 0 until n) {
            out[i] = tail[i] + bufRe[i] * window[i]
            tail[i] = bufRe[n + i] * window[n + i]
        }
    }

    private companion object {
        const val OVER_SUBTRACT = 2.0
        /** -40 dB: what the suppressor may take a bin down to. */
        const val FLOOR_POWER = 1e-4
        const val RELEASE = 0.6
        /** +4 dB a second: how fast a bin's leftover may rise when nothing pulls it down. */
        const val LEFTOVER_RISE = 1.01
        /** An estimate quieter than this (about -80 dBFS in a bin) says nothing about leftover. */
        const val MIN_ECHO_POWER = 1e4
    }
}

/**
 * Finds how many 10 ms blocks the far end's sound takes to reach the
 * microphone, from the two energy envelopes: the lag at which the log energy
 * of what was played best correlates with the log energy of what was heard.
 */
internal class EchoDelayEstimator(
    private val maxLag: Int = 60, // 600 ms
    private val history: Int = 300, // 3 s
) {
    private val cap = history + maxLag + 4
    private val far = DoubleArray(cap)
    private val mic = DoubleArray(cap)
    private var blocks = 0L
    private var candidate = -1
    private var agreed = 0

    /** The delay in blocks once it has been found, else null. */
    var delay: Int? = null
        private set

    /** The lag's correlation at the last estimate, for a test to read. */
    var confidence = 0.0
        private set

    /**
     * One block: the microphone's energy, and the energy of the far-end block
     * that lines up with it at lag 0 (null when that was never written). The
     * far end at lag L for this block is the lag-0 far end of the block L
     * earlier, so one history serves every lag.
     */
    fun add(micEnergy: Double, farLag0: Double?) {
        val i = (blocks % cap).toInt()
        mic[i] = ln(1 + micEnergy)
        far[i] = ln(1 + (farLag0 ?: 0.0))
        blocks++
        // A first answer from one second of history, then the full three seconds.
        if (blocks >= MIN_HISTORY + maxLag && blocks % 25 == 0L) estimate(minOf(history.toLong(), blocks - maxLag).toInt())
    }

    private fun estimate(history: Int) {
        // far[] holds the lag-0 far energy for every block, so the lag-L far energy
        // for block b is far[b - L]: one array, shifted.
        val end = blocks - 1
        var micMean = 0.0
        for (j in 0 until history) micMean += mic[idx(end - j)]
        micMean /= history
        var micVar = 0.0
        for (j in 0 until history) { val d = mic[idx(end - j)] - micMean; micVar += d * d }
        if (micVar <= 1e-9) return
        var best = -1
        var bestC = 0.0
        for (lag in 0..maxLag) {
            var fm = 0.0
            for (j in 0 until history) fm += far[idx(end - j - lag)]
            fm /= history
            var cov = 0.0
            var fv = 0.0
            for (j in 0 until history) {
                val df = far[idx(end - j - lag)] - fm
                cov += df * (mic[idx(end - j)] - micMean)
                fv += df * df
            }
            if (fv <= 1e-9) continue
            val c = cov / sqrt(fv * micVar)
            if (c > bestC) { bestC = c; best = lag }
        }
        confidence = bestC
        if (best < 0 || bestC < MIN_CORRELATION) return
        if (candidate >= 0 && abs(best - candidate) <= 1) agreed++ else { candidate = best; agreed = 1 }
        if (delay == null || agreed >= 2) delay = candidate
    }

    private fun idx(b: Long) = (((b % cap) + cap) % cap).toInt()

    private companion object {
        const val MIN_CORRELATION = 0.4
        const val MIN_HISTORY = 100
    }
}

/**
 * The echo canceller a call uses: the speaker sink hands it every frame it
 * plays ([farEnd]) and the microphone passes every frame it captures through it
 * ([capture]). Safe to call from the two audio threads at once.
 *
 * The two streams are tied by sample counts, not by clocks: both run at the
 * sound card's rate, so "how far the speaker's written count is ahead of the
 * microphone's read count" is steady, and the echo of a far-end sample reaches
 * the microphone a fixed number of samples after that point: the speaker
 * line's buffer, the room, and the microphone line's buffer. That number is
 * what [EchoDelayEstimator] finds.
 */
class EchoCanceller internal constructor(
    tailMs: Int = 250,
    /** The delay assumed until one is measured. */
    defaultDelayMs: Int = 100,
    /** How far before the measured delay the filter starts, so a delay that grows a little stays inside it. */
    private val marginMs: Int = 50,
) {
    constructor() : this(250, 100, 50)

    private val n = AEC_BLOCK
    private fun ms(v: Int) = v * CallAudioFormat.SAMPLE_RATE / 1000
    private val margin = ms(marginMs)
    private val mdf = Mdf(n, partitions = ms(tailMs) / n)
    private val res = ResidualEchoSuppressor(n)
    private val estimator = EchoDelayEstimator()
    private val ringSize = CallAudioFormat.SAMPLE_RATE * 4
    private val ring = FloatArray(ringSize)
    private var farCount = 0L
    private var micCount = 0L
    private var offset = Double.NaN
    private var offsetMisses = 0
    /** The offset the reference is read at: [offset] moves a little every frame, this only by whole jumps. */
    private var applied = 0L
    private var delaySamples = ms(defaultDelayMs)
    private val farBlock = DoubleArray(n)
    private val micBlock = DoubleArray(n)
    private val eBlock = DoubleArray(n)
    private val yBlock = DoubleArray(n)
    private val outBlock = DoubleArray(n)

    /** The measured delay in milliseconds, once there is one. */
    val measuredDelayMs: Int? get() = synchronized(this) { estimator.delay?.let { it * n * 1000 / CallAudioFormat.SAMPLE_RATE } }

    /** Written-minus-read samples at the last capture, raw and as applied, for tests. */
    internal val offsets: Pair<Long, Long> get() = synchronized(this) { lastRawOffset to applied }
    private var lastRawOffset = 0L

    /** The filter's leak estimate (1 untrained, near 0 converged), for tests. */
    internal val leak: Double get() = synchronized(this) { mdf.leak }

    /** Call with every frame handed to the speaker, in order, as it is handed over. */
    @Synchronized
    fun farEnd(pcm: ShortArray) {
        for (s in pcm) {
            ring[(farCount % ringSize).toInt()] = s.toFloat()
            farCount++
        }
    }

    /** Call with every captured frame (a multiple of 480 samples); returns it with the echo taken out, 10 ms later. */
    @Synchronized
    fun capture(pcm: ShortArray): ShortArray {
        require(pcm.size % n == 0) { "${pcm.size} samples is not whole 10 ms blocks" }
        val start = micCount
        micCount += pcm.size
        lastRawOffset = farCount - micCount
        val moved = trackOffset(lastRawOffset)
        if (moved != 0L) {
            // The reference moved [moved] samples, a whole number of blocks: the echo
            // now sits that many blocks later in the filter, so the filter moves too.
            val k = (moved / n).toInt()
            val newZero = start + applied
            val newFrom = newZero - maxOf(0, delaySamples - margin)
            mdf.shift(-k) { j -> DoubleArray(n).also { readFar(newFrom - j.toLong() * n, it) } }
        }
        val out = ShortArray(pcm.size)
        for (b in 0 until pcm.size / n) {
            val micStart = start + b * n
            var me = 0.0
            for (i in 0 until n) { val v = pcm[b * n + i].toDouble(); micBlock[i] = v; me += v * v }
            val zero = micStart + applied // the far-end index this block lines up with at lag 0
            estimator.add(me, farEnergy(zero))
            estimator.delay?.let { d ->
                val measured = d * n
                // The filter learns the front of its window fastest, so it is placed with
                // the direct path [margin] in. A delay that moved more than two blocks
                // moves the window, and what the filter learned moves with it.
                if (abs(measured - delaySamples) > n * 2) {
                    val k = (measured - delaySamples) / n
                    delaySamples = measured
                    val newFrom = zero - maxOf(0, delaySamples - margin)
                    mdf.shift(k) { j -> DoubleArray(n).also { readFar(newFrom - j.toLong() * n, it) } }
                }
            }
            val from = zero - maxOf(0, delaySamples - margin)
            readFar(from, farBlock)
            mdf.process(micBlock, farBlock, eBlock, yBlock)
            res.process(eBlock, yBlock, micBlock, mdf.leak, outBlock)
            for (i in 0 until n) {
                val v = outBlock[i].roundToInt()
                out[b * n + i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return out
    }

    /**
     * Smooths the written-minus-read count: writes come in bursts, the average is
     * what matters. Both lines run on the sound card's clock, so the true value
     * holds still; the reference moves only when the average has moved a whole
     * block, because to the filter every move is an echo path that changed.
     */
    private fun trackOffset(o: Long): Long {
        if (offset.isNaN()) { offset = o.toDouble(); applied = o; return 0 }
        if (abs(o - offset) > CallAudioFormat.SAMPLE_RATE / 10) {
            // A tenth of a second off for ten frames in a row: a line restarted. Follow it.
            if (++offsetMisses >= 10) { offset = o.toDouble(); offsetMisses = 0; return moveApplied() }
            return 0
        }
        offsetMisses = 0
        offset += 0.01 * (o - offset)
        return if (abs(offset - applied) > n * 3 / 2) moveApplied() else 0
    }

    /**
     * Moves [applied] towards [offset] by whole blocks only, and returns how far.
     * A move of part of a block would leave the filter's learned echo between its
     * taps and cost seconds of relearning (seen on a real line: 502 samples, 6 dB);
     * a whole-block move is followed exactly by shifting the filter. What is left
     * over, under half a block, holds still, and the filter learns it as delay.
     */
    private fun moveApplied(): Long {
        val blocks = ((offset - applied) / n).roundToLong()
        applied += blocks * n
        return blocks * n
    }

    private fun readFar(from: Long, into: DoubleArray) {
        for (i in 0 until n) {
            val at = from + i
            into[i] = if (at < 0 || at >= farCount || at < farCount - ringSize) 0.0 else ring[(at % ringSize).toInt()].toDouble()
        }
    }

    private fun farEnergy(from: Long): Double? {
        if (from < 0 || from + n > farCount || from < farCount - ringSize) return null
        var s = 0.0
        for (i in 0 until n) { val v = ring[((from + i) % ringSize).toInt()].toDouble(); s += v * v }
        return s
    }
}
