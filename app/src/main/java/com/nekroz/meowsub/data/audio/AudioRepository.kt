package com.nekroz.meowsub.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.security.MessageDigest

/** Identifies an audio document; size + modification time are used to validate the cache. */
data class AudioSource(val uri: Uri, val size: Long, val lastModified: Long)

/** Decoded audio: mono 16-bit little-endian PCM on disk plus the in-memory waveform summary. */
class LoadedAudio(
    val pcmFile: File,
    val sampleRate: Int,
    val sampleCount: Long,
    val peaks: WaveformPeaks,
) {
    val durationMs: Long get() = sampleCount * 1000 / sampleRate
}

class UnsupportedAudioException(message: String) : Exception(message)

/**
 * Decodes audio with MediaExtractor/MediaCodec into a mono PCM cache file (the equivalent of
 * Aegisub's RAM/HD audio cache) so playback and waveform rendering are sample accurate.
 * Only the most recently opened audio is kept on disk.
 */
class AudioRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, "audio")

    suspend fun load(source: AudioSource, onProgress: (Float) -> Unit): LoadedAudio =
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            val key = cacheKey(source)
            val pcm = File(cacheDir, "$key.pcm")
            val meta = File(cacheDir, "$key.peaks")
            if (pcm.exists() && meta.exists()) {
                runCatching { readMeta(meta, pcm) }.getOrNull()?.let { return@withContext it }
            }
            cacheDir.listFiles()?.forEach { it.delete() }
            val tmpPcm = File(cacheDir, "$key.pcm.tmp")
            try {
                val (sampleRate, sampleCount, peaks) = decode(source.uri, tmpPcm, onProgress)
                if (!tmpPcm.renameTo(pcm)) throw IllegalStateException("Cannot move audio cache")
                val tmpMeta = File(cacheDir, "$key.peaks.tmp")
                DataOutputStream(BufferedOutputStream(FileOutputStream(tmpMeta))).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(sampleRate)
                    out.writeLong(sampleCount)
                    peaks.writeTo(out)
                }
                tmpMeta.renameTo(meta)
                LoadedAudio(pcm, sampleRate, sampleCount, peaks)
            } catch (t: Throwable) {
                tmpPcm.delete()
                throw t
            }
        }

    private fun readMeta(meta: File, pcm: File): LoadedAudio =
        DataInputStream(meta.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC)
            val rate = input.readInt()
            val count = input.readLong()
            require(pcm.length() == count * 2)
            LoadedAudio(pcm, rate, count, WaveformPeaks.readFrom(input))
        }

    private suspend fun decode(
        uri: Uri,
        out: File,
        onProgress: (Float) -> Unit,
    ): Triple<Int, Long, WaveformPeaks> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw UnsupportedAudioException("Unsupported codec: $mime")
            }
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var builder: PeaksBuilder? = null
            var sampleCount = 0L
            var inputDone = false
            var outputDone = false
            var lastProgress = -1
            val chunk = ByteArray(64 * 1024)
            var chunkPos = 0

            BufferedOutputStream(FileOutputStream(out), 256 * 1024).use { sink ->
                fun emit(sample: Int, peaks: PeaksBuilder) {
                    if (chunkPos == chunk.size) {
                        sink.write(chunk, 0, chunkPos); chunkPos = 0
                    }
                    chunk[chunkPos++] = (sample and 0xFF).toByte()
                    chunk[chunkPos++] = (sample shr 8 and 0xFF).toByte()
                    peaks.add(sample)
                }

                while (!outputDone) {
                    ensureActive()
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                if (durationUs > 0) {
                                    val p = (extractor.sampleTime * 100 / durationUs).toInt()
                                    if (p != lastProgress) {
                                        lastProgress = p; onProgress((p / 100f).coerceIn(0f, 1f))
                                    }
                                }
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }

                        outIndex >= 0 -> {
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            val peaks = builder ?: PeaksBuilder(sampleRate, (durationUs / 1000).toInt() + 1000)
                                .also { builder = it }
                            val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            when (encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> {
                                    val sb = buf.asShortBuffer()
                                    val frames = sb.remaining() / channels
                                    repeat(frames) {
                                        var sum = 0
                                        repeat(channels) { sum += sb.get() }
                                        emit(sum / channels, peaks)
                                    }
                                    sampleCount += frames
                                }

                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val fb = buf.asFloatBuffer()
                                    val frames = fb.remaining() / channels
                                    repeat(frames) {
                                        var sum = 0f
                                        repeat(channels) { sum += fb.get() }
                                        emit((sum / channels * 32767f).toInt().coerceIn(-32768, 32767), peaks)
                                    }
                                    sampleCount += frames
                                }

                                AudioFormat.ENCODING_PCM_8BIT -> {
                                    val frames = buf.remaining() / channels
                                    repeat(frames) {
                                        var sum = 0
                                        repeat(channels) { sum += ((buf.get().toInt() and 0xFF) - 128) shl 8 }
                                        emit(sum / channels, peaks)
                                    }
                                    sampleCount += frames
                                }

                                else -> throw UnsupportedAudioException("Unsupported PCM encoding: $encoding")
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
                if (chunkPos > 0) sink.write(chunk, 0, chunkPos)
            }
            onProgress(1f)
            val peaks = builder?.build() ?: throw UnsupportedAudioException("Empty audio")
            Triple(sampleRate, sampleCount, peaks)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun cacheKey(source: AudioSource): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${source.uri}|${source.size}|${source.lastModified}".toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAGIC = 0x4D534150 // "MSAP"
    }
}
