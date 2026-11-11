package ai.ciris.mobile.shared.calls.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A complex DFT of any length whose prime factors are 2, 3 and 5: the echo
 * canceller works in 10 ms blocks of 480 samples at 48 kHz, so its transforms
 * are 960 points, which is not a power of two.
 *
 * Mixed-radix decimation in time, the textbook Cooley-Tukey split: a length
 * `p·m` transform is `p` transforms of length `m` (every `p`th input), joined
 * by twiddled `p`-point DFTs. Radix 4 is taken first, then 2, 3 and 5. One
 * instance is not safe to use from two threads at once.
 */
internal class Fft(val n: Int) {
    private val factors: IntArray
    private val cosT = DoubleArray(n)
    private val sinT = DoubleArray(n)
    private val tr = DoubleArray(5)
    private val ti = DoubleArray(5)
    private val conjRe = DoubleArray(n)
    private val conjIm = DoubleArray(n)

    init {
        require(n >= 1) { "length $n" }
        val f = ArrayList<Int>()
        var r = n
        for (p in intArrayOf(4, 2, 3, 5)) while (r % p == 0) { f += p; r /= p }
        require(r == 1) { "$n has a prime factor other than 2, 3 and 5" }
        factors = f.toIntArray()
        for (i in 0 until n) {
            val a = -2 * PI * i / n
            cosT[i] = cos(a)
            sinT[i] = sin(a)
        }
    }

    /** `out = DFT(in)`. The input arrays are not changed and may not be the output arrays. */
    fun forward(inRe: DoubleArray, inIm: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        step(inRe, inIm, 0, 1, n, outRe, outIm, 0, 0)
    }

    /** `out = DFT⁻¹(in)`, scaled by 1/n, so `inverse(forward(x)) == x`. */
    fun inverse(inRe: DoubleArray, inIm: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        for (i in 0 until n) { conjRe[i] = inRe[i]; conjIm[i] = -inIm[i] }
        step(conjRe, conjIm, 0, 1, n, outRe, outIm, 0, 0)
        val s = 1.0 / n
        for (i in 0 until n) { outRe[i] *= s; outIm[i] = -outIm[i] * s }
    }

    private fun step(
        xr: DoubleArray, xi: DoubleArray, xOff: Int, stride: Int, len: Int,
        yr: DoubleArray, yi: DoubleArray, yOff: Int, level: Int,
    ) {
        if (len == 1) {
            yr[yOff] = xr[xOff]
            yi[yOff] = xi[xOff]
            return
        }
        val p = factors[level]
        val m = len / p
        for (j in 0 until p) step(xr, xi, xOff + j * stride, stride * p, m, yr, yi, yOff + j * m, level + 1)
        val tw = n / len // W_len^e == W_n^(e·tw)
        val pw = n / p // W_p^e == W_n^(e·pw)
        for (k in 0 until m) {
            for (q in 0 until p) {
                val at = yOff + q * m + k
                val e = (q * k * tw) % n
                val c = cosT[e]
                val s = sinT[e]
                val a = yr[at]
                val b = yi[at]
                tr[q] = a * c - b * s
                ti[q] = a * s + b * c
            }
            for (q2 in 0 until p) {
                var sr = 0.0
                var si = 0.0
                for (q in 0 until p) {
                    val e = ((q * q2) % p) * pw
                    val c = cosT[e]
                    val s = sinT[e]
                    sr += tr[q] * c - ti[q] * s
                    si += tr[q] * s + ti[q] * c
                }
                yr[yOff + q2 * m + k] = sr
                yi[yOff + q2 * m + k] = si
            }
        }
    }
}
