package com.nekroz.meowsub.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.random.Random

class DecodeMathTest {

    /** Straightforward per-millisecond min / max / mean |x|, as the waveform defines it. */
    private fun reference(samples: ShortArray, rate: Int): WaveformPeaks {
        val buckets = ((samples.size.toLong() * 1000 + rate - 1) / rate).toInt()
        val mins = ShortArray(buckets)
        val maxs = ShortArray(buckets)
        val avgs = ShortArray(buckets)
        var used = 0
        for (ms in 0 until buckets) {
            val from = ((ms.toLong() * rate + 999) / 1000).toInt()
            val to = minOf(samples.size, (((ms + 1).toLong() * rate + 999) / 1000).toInt())
            if (from >= to) continue
            var lo = 0
            var hi = 0
            var sum = 0L
            for (i in from until to) {
                val s = samples[i].toInt()
                lo = minOf(lo, s); hi = maxOf(hi, s); sum += kotlin.math.abs(s)
            }
            mins[ms] = lo.toShort(); maxs[ms] = hi.toShort(); avgs[ms] = (sum / (to - from)).toShort()
            used = ms + 1
        }
        return WaveformPeaks(mins.copyOf(used), maxs.copyOf(used), avgs.copyOf(used))
    }

    @Test
    fun peaksMatchReferenceWhateverTheChunking() {
        val random = Random(1)
        for (rate in listOf(8000, 22050, 44100, 48000)) {
            val samples = ShortArray(rate * 3 + 17) { random.nextInt(-32768, 32768).toShort() }
            val expected = reference(samples, rate)
            for (chunk in listOf(1, 7, 1024, 4096, samples.size)) {
                val builder = PeaksBuilder(rate, 100)
                var i = 0
                while (i < samples.size) {
                    val n = minOf(chunk, samples.size - i)
                    builder.addAll(samples.copyOfRange(i, i + n), n)
                    i += n
                }
                val actual = builder.build()
                assertArrayEquals("min rate=$rate chunk=$chunk", expected.mins, actual.mins)
                assertArrayEquals("max rate=$rate chunk=$chunk", expected.maxs, actual.maxs)
                assertArrayEquals("avg rate=$rate chunk=$chunk", expected.avgs, actual.avgs)
            }
        }
    }

    @Test
    fun downmixAveragesChannels() {
        val mono = ShortArray(2)
        Downmix.pcm16(shortArrayOf(100, 300, -5, -2), 2, 2, mono)
        assertArrayEquals(shortArrayOf(200, -3), mono)
        Downmix.pcm16(shortArrayOf(3, 6, 9, -3, -6, -9), 3, 2, mono)
        assertArrayEquals(shortArrayOf(6, -6), mono)
        Downmix.pcm16(shortArrayOf(42, -42), 1, 2, mono)
        assertArrayEquals(shortArrayOf(42, -42), mono)
        Downmix.float(floatArrayOf(1f, 1f, -2f, -2f), 2, 2, mono)
        assertArrayEquals(shortArrayOf(32767, -32768), mono)
        Downmix.pcm8(byteArrayOf(128.toByte(), 255.toByte()), 1, 2, mono)
        assertArrayEquals(shortArrayOf(0, (127 shl 8).toShort()), mono)
    }
}
