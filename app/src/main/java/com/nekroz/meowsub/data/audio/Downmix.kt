package com.nekroz.meowsub.data.audio

/**
 * Averages interleaved decoder output into mono 16-bit samples. Plain array loops: this runs
 * for every sample of the file, so it must stay free of per-sample calls.
 */
internal object Downmix {

    fun pcm16(src: ShortArray, channels: Int, frames: Int, dst: ShortArray) {
        when (channels) {
            1 -> src.copyInto(dst, 0, 0, frames)
            2 -> for (f in 0 until frames) {
                dst[f] = ((src[2 * f] + src[2 * f + 1]) / 2).toShort()
            }

            else -> {
                var i = 0
                for (f in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += src[i++]
                    dst[f] = (sum / channels).toShort()
                }
            }
        }
    }

    fun float(src: FloatArray, channels: Int, frames: Int, dst: ShortArray) {
        var i = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += src[i++]
            dst[f] = (sum / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** 8-bit PCM is unsigned, centred on 128. */
    fun pcm8(src: ByteArray, channels: Int, frames: Int, dst: ShortArray) {
        var i = 0
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += ((src[i++].toInt() and 0xFF) - 128) shl 8
            dst[f] = (sum / channels).toShort()
        }
    }
}
