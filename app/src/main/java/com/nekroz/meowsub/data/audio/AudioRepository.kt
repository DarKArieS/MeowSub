package com.nekroz.meowsub.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
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
        var callbackThread: HandlerThread? = null
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
            // Pack many compressed frames into each input buffer when the codec allows it: one
            // IPC round trip per input buffer instead of per ~20 ms frame. Needs callback mode.
            val batch = codec.codecInfo.getCapabilitiesForType(mime)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_MultipleFrames)
            if (batch) {
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BATCH_INPUT_BYTES)
                format.setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
                format.setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, BATCH_OUTPUT_BYTES / 2)
            }
            // The codec's callbacks feed it input on their own thread; decoded output comes back
            // here through [events], so feeding overlaps with the conversion below.
            val events = Channel<CodecEvent>(Channel.UNLIMITED)
            val thread = HandlerThread("AudioDecoder").apply { start() }
            callbackThread = thread
            codec.setCallback(DecoderCallback(extractor, batch, events), Handler(thread.looper))
            codec.configure(format, null, null, 0)
            codec.start()

            var builder: PeaksBuilder? = null
            var sampleCount = 0L
            var lastProgress = -1
            // Scratch arrays, grown as needed and reused for every output buffer.
            var interleaved = ShortArray(0)
            var floats = FloatArray(0)
            var bytes8 = ByteArray(0)
            var mono = ShortArray(0)
            var monoBytes = ByteArray(0)

            BufferedOutputStream(FileOutputStream(out), 256 * 1024).use { sink ->
                var outputDone = false
                while (!outputDone) {
                    val output = when (val event = events.receive()) {
                        is CodecEvent.Failed -> throw event.error
                        is CodecEvent.FormatChanged -> {
                            val f = event.format
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            continue
                        }

                        is CodecEvent.Output -> event
                    }
                    if (output.endOfStream) outputDone = true
                    val peaks = builder ?: PeaksBuilder(sampleRate, (durationUs / 1000).toInt() + 1000)
                        .also { builder = it }
                    val buf = codec.getOutputBuffer(output.index)!!.order(ByteOrder.nativeOrder())
                    val bytesPerSample = when (encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> 2
                        AudioFormat.ENCODING_PCM_FLOAT -> 4
                        AudioFormat.ENCODING_PCM_8BIT -> 1
                        else -> throw UnsupportedAudioException("Unsupported PCM encoding: $encoding")
                    }
                    val total = output.ranges.sumOf { it.second } / bytesPerSample
                    when (encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> if (interleaved.size < total) interleaved = ShortArray(total)
                        AudioFormat.ENCODING_PCM_FLOAT -> if (floats.size < total) floats = FloatArray(total)
                        else -> if (bytes8.size < total) bytes8 = ByteArray(total)
                    }
                    // Bulk-copy the decoded samples out, then hand the buffer straight back.
                    var n = 0
                    for ((offset, size) in output.ranges) {
                        buf.limit(offset + size)
                        buf.position(offset)
                        val count = size / bytesPerSample
                        when (encoding) {
                            AudioFormat.ENCODING_PCM_16BIT -> buf.asShortBuffer().get(interleaved, n, count)
                            AudioFormat.ENCODING_PCM_FLOAT -> buf.asFloatBuffer().get(floats, n, count)
                            else -> buf.get(bytes8, n, count)
                        }
                        n += count
                    }
                    codec.releaseOutputBuffer(output.index, false)

                    val frames = n / channels
                    if (mono.size < frames) {
                        mono = ShortArray(frames)
                        monoBytes = ByteArray(frames * 2)
                    }
                    when (encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> Downmix.pcm16(interleaved, channels, frames, mono)
                        AudioFormat.ENCODING_PCM_FLOAT -> Downmix.float(floats, channels, frames, mono)
                        else -> Downmix.pcm8(bytes8, channels, frames, mono)
                    }
                    peaks.addAll(mono, frames)
                    ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mono, 0, frames)
                    sink.write(monoBytes, 0, frames * 2)
                    sampleCount += frames

                    if (durationUs > 0) {
                        val p = (output.timeUs * 100 / durationUs).toInt()
                        if (p != lastProgress) {
                            lastProgress = p; onProgress((p / 100f).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            onProgress(1f)
            val peaks = builder?.build() ?: throw UnsupportedAudioException("Empty audio")
            Triple(sampleRate, sampleCount, peaks)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            // The extractor is used on the callback thread: let it finish before releasing.
            callbackThread?.let { it.quitSafely(); it.join() }
            extractor.release()
        }
    }

    private sealed interface CodecEvent {
        /** A decoded output buffer; [ranges] are its (offset, size) byte ranges, in order. */
        class Output(val index: Int, val ranges: List<Pair<Int, Int>>, val timeUs: Long, val endOfStream: Boolean) : CodecEvent
        class FormatChanged(val format: MediaFormat) : CodecEvent
        class Failed(val error: Throwable) : CodecEvent
    }

    /** Feeds the codec from [extractor] and forwards everything it reports to [events]. */
    private class DecoderCallback(
        private val extractor: MediaExtractor,
        private val batch: Boolean,
        private val events: Channel<CodecEvent>,
    ) : MediaCodec.Callback() {
        private var inputDone = false
        private val frames = java.util.ArrayDeque<MediaCodec.BufferInfo>()

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (inputDone) return
            try {
                fill(codec, index)
            } catch (e: Exception) {
                inputDone = true
                events.trySend(CodecEvent.Failed(e))
            }
        }

        /** Packs the input buffer with the next frames (just one unless [batch]), or end of stream. */
        private fun fill(codec: MediaCodec, index: Int) {
            val buf = codec.getInputBuffer(index)!!
            var offset = 0
            frames.clear()
            while (true) {
                val size = extractor.sampleSize
                if (size < 0 || (frames.isNotEmpty() && (!batch || offset + size > buf.capacity()))) break
                val read = extractor.readSampleData(buf, offset)
                if (read < 0) break
                frames.add(MediaCodec.BufferInfo().apply { set(offset, read, extractor.sampleTime, 0) })
                offset += read
                extractor.advance()
            }
            if (frames.isEmpty()) {
                inputDone = true
                frames.add(MediaCodec.BufferInfo().apply { set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) })
            }
            if (batch) {
                codec.queueInputBuffers(index, frames)
            } else {
                val f = frames.first
                codec.queueInputBuffer(index, f.offset, f.size, f.presentationTimeUs, f.flags)
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            events.trySend(output(index, listOf(info)))
        }

        override fun onOutputBuffersAvailable(codec: MediaCodec, index: Int, infos: java.util.ArrayDeque<MediaCodec.BufferInfo>) {
            events.trySend(output(index, infos.toList()))
        }

        private fun output(index: Int, infos: List<MediaCodec.BufferInfo>) = CodecEvent.Output(
            index = index,
            ranges = infos.filter { it.size > 0 }.map { it.offset to it.size },
            timeUs = infos.first().presentationTimeUs,
            endOfStream = infos.any { it.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 },
        )

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            events.trySend(CodecEvent.FormatChanged(format))
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            events.trySend(CodecEvent.Failed(e))
        }
    }

    private fun cacheKey(source: AudioSource): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${source.uri}|${source.size}|${source.lastModified}".toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAGIC = 0x4D534150 // "MSAP"

        /** Compressed bytes packed into one input buffer when the codec takes several frames. */
        const val BATCH_INPUT_BYTES = 256 * 1024

        /** Decoded bytes per output buffer in that mode (~1.5 s of 44.1 kHz stereo). */
        const val BATCH_OUTPUT_BYTES = 256 * 1024
    }
}
