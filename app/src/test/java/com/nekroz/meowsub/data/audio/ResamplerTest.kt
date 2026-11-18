package com.nekroz.meowsub.data.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    private fun sine(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    /** RMS away from the edges, where the filter sees zeros beyond the input. */
    private fun rms(x: FloatArray): Double {
        val from = x.size / 10
        val to = x.size - from
        var sum = 0.0
        for (i in from until to) sum += x[i] * x[i].toDouble()
        return sqrt(sum / (to - from))
    }

    /** Amplitude of [freq] in [x] (sampled at [rate]), by correlating with a sine and cosine. */
    private fun amplitudeAt(x: FloatArray, freq: Double, rate: Int): Double {
        val from = x.size / 10
        val to = x.size - from
        var s = 0.0
        var c = 0.0
        for (i in from until to) {
            s += x[i] * sin(2 * PI * freq * i / rate)
            c += x[i] * kotlin.math.cos(2 * PI * freq * i / rate)
        }
        return 2 * sqrt(s * s + c * c) / (to - from)
    }

    @Test
    fun lengthFollowsTheRateRatio() {
        for (rate in listOf(8000, 22050, 32000, 44100, 48000)) {
            val out = Resampler.resample(FloatArray(rate * 2), rate, 16_000)
            assertEquals("rate $rate", 32_000, out.size)
        }
    }

    @Test
    fun speechBandSurvivesDownsampling() {
        for (rate in listOf(44100, 48000, 22050)) {
            for (freq in listOf(200.0, 1000.0, 3000.0, 6500.0)) {
                val out = Resampler.resample(sine(freq, rate, 1.0), rate, 16_000)
                assertEquals("$freq Hz from $rate", 0.5, amplitudeAt(out, freq, 16_000), 0.02)
            }
        }
    }

    @Test
    fun aboveNyquistIsFilteredInsteadOfAliasing() {
        // 11 kHz would alias to 5 kHz at 16 kHz without a low-pass filter.
        val out = Resampler.resample(sine(11_000.0, 44_100, 1.0), 44_100, 16_000)
        assertTrue("residual rms ${rms(out)}", rms(out) < 0.005)
        assertTrue(amplitudeAt(out, 5000.0, 16_000) < 0.005)
    }

    @Test
    fun upsamplingKeepsTheTone() {
        val out = Resampler.resample(sine(1000.0, 8000, 1.0), 8000, 16_000)
        assertEquals(0.5, amplitudeAt(out, 1000.0, 16_000), 0.02)
        assertTrue(abs(rms(out) - 0.5 / sqrt(2.0)) < 0.01)
    }

    @Test
    fun sameRateIsACopy() {
        val x = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertTrue(x.contentEquals(Resampler.resample(x, 16_000, 16_000)))
    }
}
