package com.nekroz.meowsub.data.transcribe

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ModelLoadException(message: String) : Exception(message)

/**
 * Holds at most one whisper.cpp model in memory and runs transcriptions one at a time.
 * Loading happens on demand ([transcribe] loads the requested model first) and stays until
 * [unload] or a different model is requested.
 */
class WhisperEngine(context: Context) {

    sealed interface State {
        data object Unloaded : State
        data class Loading(val name: String) : State
        data class Loaded(val name: String) : State
    }

    private val resolver = context.applicationContext.contentResolver
    private val mutex = Mutex()
    private var ctx = 0L
    private var loadedUri: String? = null

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Transcribes 16 kHz mono [samples] with the model at [settings]'s URI, loading it first if
     * needed. Cancelling the calling coroutine aborts whisper.cpp (model loading is not
     * interruptible, so a cancelled load still finishes and the model stays loaded).
     */
    suspend fun transcribe(samples: FloatArray, settings: TranscribeSettings): String = mutex.withLock {
        val uri = checkNotNull(settings.modelUri) { "No model selected" }
        loadLocked(uri, settings.modelName ?: uri)
        val handle = ctx
        WhisperNative.resetAbort()
        coroutineScope {
            // Turns cancellation of this coroutine into an abort of the native call.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    WhisperNative.abort()
                }
            }
            try {
                val start = System.nanoTime()
                val bytes = withContext(Dispatchers.Default) {
                    WhisperNative.transcribe(
                        handle, samples, settings.threads, settings.language, settings.beamSize,
                        settings.prompt, settings.audioCtx(samples.size),
                    )
                }
                Log.i(TAG, "Transcribed ${samples.size * 1000L / AudioClip.WHISPER_RATE} ms of audio in ${(System.nanoTime() - start) / 1_000_000} ms")
                ensureActive()
                TranscriptCleanup.collapseRepeats(checkNotNull(bytes) { "whisper.cpp failed" }.toString(Charsets.UTF_8))
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Frees the loaded model, waiting for a running transcription to end first. */
    suspend fun unload() = mutex.withLock { freeLocked() }

    private suspend fun loadLocked(uri: String, name: String) {
        if (ctx != 0L && loadedUri == uri) return
        freeLocked()
        _state.value = State.Loading(name)
        // Not cancellable: whisper.cpp can't stop mid-load, and the context must not leak.
        val loaded = withContext(NonCancellable + Dispatchers.IO) {
            val start = System.nanoTime()
            val pfd = try {
                resolver.openFileDescriptor(uri.toUri(), "r")
            } catch (e: Exception) {
                null
            } ?: return@withContext 0L
            WhisperNative.load(pfd.detachFd(), flashAttn = true).also {
                Log.i(TAG, "Loaded $name in ${(System.nanoTime() - start) / 1_000_000} ms: ${WhisperNative.systemInfo()}")
            }
        }
        if (loaded == 0L) {
            _state.value = State.Unloaded
            throw ModelLoadException(name)
        }
        ctx = loaded
        loadedUri = uri
        _state.value = State.Loaded(name)
        currentCoroutineContext().ensureActive()
    }

    private suspend fun freeLocked() {
        if (ctx == 0L) return
        val handle = ctx
        ctx = 0L
        loadedUri = null
        withContext(NonCancellable + Dispatchers.IO) { WhisperNative.free(handle) }
        _state.value = State.Unloaded
    }

    private companion object {
        const val TAG = "WhisperEngine"
    }
}
