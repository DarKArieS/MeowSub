package com.nekroz.meowsub.data.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Waveform summary at 1 ms resolution: for each millisecond the minimum and maximum sample,
 * and the mean absolute amplitude (Aegisub's "maximum + average" style).
 */
class WaveformPeaks(
    val mins: ShortArray,
    val maxs: ShortArray,
    val avgs: ShortArray,
) {
    val sizeMs: Int get() = mins.size

    fun writeTo(out: DataOutputStream) {
        out.writeInt(sizeMs)
        for (arr in arrayOf(mins, maxs, avgs)) {
            val bb = ByteBuffer.allocate(arr.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(arr)
            out.write(bb.array())
        }
    }

    companion object {
        fun readFrom(input: DataInputStream): WaveformPeaks {
            val n = input.readInt()
            require(n in 0..200_000_000)
            fun read(): ShortArray {
                val bytes = ByteArray(n * 2)
                input.readFully(bytes)
                val arr = ShortArray(n)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(arr)
                return arr
            }
            return WaveformPeaks(read(), read(), read())
        }
    }
}

/** Streams mono samples in and accumulates per-millisecond peaks. */
internal class PeaksBuilder(private val sampleRate: Int, estimatedMs: Int) {
    private var mins = ShortArray(estimatedMs.coerceAtLeast(1024))
    private var maxs = ShortArray(mins.size)
    private var avgs = ShortArray(mins.size)

    private var bucket = 0
    private var nextBoundary = boundaryOf(1)
    private var sampleIndex = 0L
    private var curMin = 0
    private var curMax = 0
    private var sumAbs = 0L
    private var count = 0
    private var filled = 0

    private fun boundaryOf(ms: Int): Long = (ms.toLong() * sampleRate + 999) / 1000

    fun add(sample: Int) {
        if (sampleIndex >= nextBoundary) {
            flush()
            bucket++
            nextBoundary = boundaryOf(bucket + 1)
        }
        if (sample < curMin) curMin = sample
        if (sample > curMax) curMax = sample
        sumAbs += if (sample < 0) -sample else sample
        count++
        sampleIndex++
    }

    private fun flush() {
        if (bucket >= mins.size) {
            val newSize = maxOf(bucket + 1, mins.size * 3 / 2)
            mins = mins.copyOf(newSize)
            maxs = maxs.copyOf(newSize)
            avgs = avgs.copyOf(newSize)
        }
        mins[bucket] = curMin.toShort()
        maxs[bucket] = curMax.toShort()
        avgs[bucket] = (if (count > 0) sumAbs / count else 0).toShort()
        filled = bucket + 1
        curMin = 0; curMax = 0; sumAbs = 0; count = 0
    }

    fun build(): WaveformPeaks {
        if (count > 0) flush()
        return WaveformPeaks(mins.copyOf(filled), maxs.copyOf(filled), avgs.copyOf(filled))
    }
}
