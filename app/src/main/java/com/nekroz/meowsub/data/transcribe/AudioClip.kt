package com.nekroz.meowsub.data.transcribe

import com.nekroz.meowsub.data.audio.LoadedAudio
import com.nekroz.meowsub.data.audio.Resampler
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cuts audio out of the decoded PCM cache in the format Whisper takes. */
object AudioClip {

    const val WHISPER_RATE = 16_000

    /** Whisper skips input under 100 ms and does poorly on tiny clips: pad with silence to 1 s. */
    private const val MIN_SAMPLES = WHISPER_RATE

    /** [fromMs] to [toMs] (clamped to the audio) as 16 kHz mono floats in -1..1. */
    fun read(audio: LoadedAudio, fromMs: Long, toMs: Long): FloatArray {
        val rate = audio.sampleRate
        val first = (fromMs.coerceAtLeast(0) * rate / 1000).coerceAtMost(audio.sampleCount)
        val last = (toMs.coerceAtLeast(0) * rate / 1000).coerceIn(first, audio.sampleCount)
        val count = (last - first).toInt()
        val bytes = ByteArray(count * 2)
        RandomAccessFile(audio.pcmFile, "r").use { file ->
            file.seek(first * 2)
            file.readFully(bytes)
        }
        val shorts = ShortArray(count)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val floats = FloatArray(count) { shorts[it] / 32768f }
        val clip = Resampler.resample(floats, rate, WHISPER_RATE)
        return if (clip.size >= MIN_SAMPLES) clip else clip.copyOf(MIN_SAMPLES)
    }
}
