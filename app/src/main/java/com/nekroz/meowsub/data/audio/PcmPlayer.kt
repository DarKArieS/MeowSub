package com.nekroz.meowsub.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile

/**
 * Plays ranges of the mono 16-bit PCM cache through a streaming [AudioTrack].
 * Position is derived from the track's playback head, so it is sample accurate.
 */
class PcmPlayer(
    private val pcmFile: File,
    val sampleRate: Int,
    private val sampleCount: Long,
) {
    @Volatile
    private var session: Session? = null

    @Volatile
    var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            session?.let { runCatching { it.track.setVolume(field) } }
        }

    val isPlaying: Boolean get() = session?.finished == false

    /** Current playback position in ms, or null when nothing is playing. */
    fun positionMs(): Long? {
        val s = session ?: return null
        if (s.finished) return null
        val head = runCatching { s.track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        return (s.startSample + head) * 1000 / sampleRate
    }

    /** Plays from [fromMs] to [toMs] (or to the end when null). */
    fun play(fromMs: Long, toMs: Long?) {
        stop()
        val start = (fromMs.coerceAtLeast(0) * sampleRate / 1000).coerceAtMost(sampleCount)
        val end = ((toMs ?: Long.MAX_VALUE / sampleRate).coerceAtLeast(0) * sampleRate / 1000).coerceAtMost(sampleCount)
        if (end <= start) return

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate / 10 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.setVolume(volume)
        val s = Session(track, start, end)
        session = s
        track.play()
        s.start()
    }

    fun stop() {
        val s = session ?: return
        session = null
        s.stopped = true
        runCatching { s.track.pause(); s.track.flush() }
        s.join(300)
        runCatching { s.track.release() }
    }

    fun release() = stop()

    private inner class Session(
        val track: AudioTrack,
        val startSample: Long,
        val endSample: Long,
    ) : Thread("PcmPlayer") {
        @Volatile var stopped = false
        @Volatile var finished = false

        override fun run() {
            try {
                var written = 0L
                RandomAccessFile(pcmFile, "r").use { raf ->
                    raf.seek(startSample * 2)
                    val buf = ByteArray(16 * 1024)
                    var remaining = (endSample - startSample) * 2
                    while (!stopped && remaining > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        var off = 0
                        while (off < n && !stopped) {
                            val w = track.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                            if (w <= 0) {
                                stopped = true; break
                            }
                            off += w
                        }
                        written += off
                        remaining -= n
                    }
                }
                if (!stopped) {
                    // Let the buffered tail play out, then report completion.
                    val frames = written / 2
                    track.stop()
                    var lastHead = -1L
                    var stalled = 0
                    while (!stopped) {
                        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        if (head >= frames) break
                        if (head == lastHead) {
                            if (++stalled > 100) break
                        } else stalled = 0
                        lastHead = head
                        sleep(10)
                    }
                }
            } catch (_: Exception) {
                // Track released underneath us during stop(); nothing to do.
            } finally {
                finished = true
            }
        }
    }
}
