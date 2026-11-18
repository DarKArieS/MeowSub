package com.nekroz.meowsub.data.transcribe

/** whisper.cpp through JNI (app/src/main/cpp/whisper_jni.cpp). Use via [WhisperEngine]. */
internal object WhisperNative {
    init {
        System.loadLibrary("meowsub_whisper")
    }

    /** Loads a ggml model from [fd], taking ownership of it. Returns a context, or 0 on failure. */
    external fun load(fd: Int, flashAttn: Boolean): Long

    external fun free(ctx: Long)

    /** Clears the abort flag; call before starting a transcription. */
    external fun resetAbort()

    /** Makes the running [transcribe] return null as soon as possible. */
    external fun abort()

    /**
     * Transcribes 16 kHz mono [samples] into one segment. [language] is a Whisper code or
     * "auto"; [beamSize] 1 means greedy decoding; [audioCtx] 0 keeps the full 30 s context.
     * Returns the text as UTF-8, or null when aborted or failed.
     */
    external fun transcribe(
        ctx: Long,
        samples: FloatArray,
        threads: Int,
        language: String,
        beamSize: Int,
        prompt: String,
        audioCtx: Int,
    ): ByteArray?

    external fun systemInfo(): String
}
