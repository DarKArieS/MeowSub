package com.nekroz.meowsub.data.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sample-rate conversion with a Hann-windowed sinc low-pass, precomputed per phase
 * (polyphase). When downsampling, the cut-off follows the target rate, so speech above its
 * Nyquist frequency is filtered out instead of aliasing into the result.
 */
object Resampler {

    /** Zero crossings of the sinc on each side, at the lower of the two rates. */
    private const val ZERO_CROSSINGS = 16

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0)
        if (fromRate == toRate) return input.copyOf()
        val g = gcd(fromRate, toRate)
        val up = toRate / g // output step in "1/up input sample" units: out[i] sits at i * down / up
        val down = fromRate / g
        val scale = minOf(1.0, toRate.toDouble() / fromRate) // cut-off relative to the input Nyquist
        val cutoff = scale * 0.95
        val half = ceil(ZERO_CROSSINGS / scale).toInt()
        val taps = 2 * half

        // weights[p * taps + k] multiplies input[base - half + 1 + k] for phase p / up.
        val weights = FloatArray(up * taps)
        for (p in 0 until up) {
            val frac = p.toDouble() / up
            var sum = 0.0
            for (k in 0 until taps) {
                val x = (k - half + 1) - frac
                val w = sinc(x * cutoff) * hann(x / half)
                weights[p * taps + k] = w.toFloat()
                sum += w
            }
            for (k in 0 until taps) weights[p * taps + k] = (weights[p * taps + k] / sum).toFloat() // unity gain
        }

        val outLen = (input.size.toLong() * up / down).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toLong() * down
            val base = (pos / up).toInt()
            val row = (pos % up).toInt() * taps
            val first = base - half + 1
            var acc = 0f
            if (first >= 0 && first + taps <= input.size) {
                for (k in 0 until taps) acc += input[first + k] * weights[row + k]
            } else {
                for (k in 0 until taps) {
                    val j = first + k
                    if (j in input.indices) acc += input[j] * weights[row + k]
                }
            }
            out[i] = acc
        }
        return out
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun hann(t: Double): Double = if (t <= -1.0 || t >= 1.0) 0.0 else 0.5 * (1 + cos(PI * t))

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
