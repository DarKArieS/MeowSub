package com.nekroz.meowsub.data.transcribe

import android.content.Context
import androidx.core.content.edit
import kotlin.math.ceil

/** User settings for speech-to-text with whisper.cpp. */
data class TranscribeSettings(
    /** `content://` URI of the ggml model file, with a persisted read permission. */
    val modelUri: String? = null,
    val modelName: String? = null,
    /** Whisper language code, or [AUTO] to detect it. */
    val language: String = AUTO,
    val threads: Int = defaultThreads(),
    /** 1 = greedy decoding (fastest); more = beam search (slower, often more accurate). */
    val beamSize: Int = 1,
    /** Shrink the encoder's 30 s window to the clip's length: much faster for short lines. */
    val fitContextToClip: Boolean = true,
    /** Audio added before and after the line, so clipped word edges are still heard. */
    val paddingMs: Int = 0,
    /** Initial prompt: steers style, e.g. script (traditional / simplified) and punctuation. */
    val prompt: String = "",
) {
    /** Encoder context for a clip of [sampleCount] 16 kHz samples (0 = the full window). */
    fun audioCtx(sampleCount: Int): Int {
        if (!fitContextToClip) return 0
        // 50 encoder frames per second, plus some slack; very small contexts make Whisper loop.
        val frames = ceil(sampleCount / 16_000.0 * 50).toInt() + CONTEXT_SLACK
        return frames.coerceIn(MIN_CONTEXT, FULL_CONTEXT).let { if (it >= FULL_CONTEXT) 0 else it }
    }

    companion object {
        const val AUTO = "auto"
        const val MAX_BEAM = 8
        const val MAX_PADDING_MS = 1000
        private const val FULL_CONTEXT = 1500
        private const val MIN_CONTEXT = 256
        private const val CONTEXT_SLACK = 64

        /** Whisper language codes offered in the settings, in display order. */
        val LANGUAGES = listOf(AUTO, "ja", "zh", "en", "ko", "yue", "fr", "de", "es", "ru", "th", "vi")

        fun maxThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        fun defaultThreads(): Int = maxThreads().coerceAtMost(6)
    }
}

/** Stores [TranscribeSettings] in SharedPreferences. */
class TranscribeSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("transcribe", Context.MODE_PRIVATE)

    fun load(): TranscribeSettings {
        val d = TranscribeSettings()
        return TranscribeSettings(
            modelUri = prefs.getString(KEY_MODEL_URI, null),
            modelName = prefs.getString(KEY_MODEL_NAME, null),
            language = prefs.getString(KEY_LANGUAGE, null)?.takeIf { it in TranscribeSettings.LANGUAGES } ?: d.language,
            threads = prefs.getInt(KEY_THREADS, d.threads).coerceIn(1, TranscribeSettings.maxThreads()),
            beamSize = prefs.getInt(KEY_BEAM, d.beamSize).coerceIn(1, TranscribeSettings.MAX_BEAM),
            fitContextToClip = prefs.getBoolean(KEY_FIT_CONTEXT, d.fitContextToClip),
            paddingMs = prefs.getInt(KEY_PADDING, d.paddingMs).coerceIn(0, TranscribeSettings.MAX_PADDING_MS),
            prompt = prefs.getString(KEY_PROMPT, d.prompt).orEmpty(),
        )
    }

    fun save(s: TranscribeSettings) {
        prefs.edit {
            putString(KEY_MODEL_URI, s.modelUri)
            putString(KEY_MODEL_NAME, s.modelName)
            putString(KEY_LANGUAGE, s.language)
            putInt(KEY_THREADS, s.threads)
            putInt(KEY_BEAM, s.beamSize)
            putBoolean(KEY_FIT_CONTEXT, s.fitContextToClip)
            putInt(KEY_PADDING, s.paddingMs)
            putString(KEY_PROMPT, s.prompt)
        }
    }

    private companion object {
        const val KEY_MODEL_URI = "modelUri"
        const val KEY_MODEL_NAME = "modelName"
        const val KEY_LANGUAGE = "language"
        const val KEY_THREADS = "threads"
        const val KEY_BEAM = "beamSize"
        const val KEY_FIT_CONTEXT = "fitContext"
        const val KEY_PADDING = "paddingMs"
        const val KEY_PROMPT = "prompt"
    }
}
