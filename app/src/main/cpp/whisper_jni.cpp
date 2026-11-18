// JNI bridge to whisper.cpp for com.nekroz.meowsub.data.transcribe.WhisperNative.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdio>
#include <string>
#include <unistd.h>

#include "whisper.h"

#define TAG "MeowWhisper"

namespace {

// Set by abort(), checked by whisper.cpp before each graph computation. One transcription
// runs at a time (the Kotlin side serialises them), so a single flag is enough.
std::atomic<bool> g_abort{false};

// Silences whisper.cpp's error log during the deliberately aborted priming call.
std::atomic<bool> g_quiet{false};

size_t read_file(void *ctx, void *out, size_t n) { return fread(out, 1, n, static_cast<FILE *>(ctx)); }
bool eof_file(void *ctx) { return feof(static_cast<FILE *>(ctx)) != 0; }
void close_file(void *ctx) { fclose(static_cast<FILE *>(ctx)); }

void log_to_logcat(ggml_log_level level, const char *text, void *) {
    // whisper.cpp's info logs are chatty (one line per tensor); keep warnings and errors.
    if (g_quiet) return;
    if (level == GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, TAG, text);
    else if (level == GGML_LOG_LEVEL_ERROR) __android_log_write(ANDROID_LOG_ERROR, TAG, text);
}

std::string to_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

extern "C" {

/** Loads a ggml model from [fd], which this call takes over and closes. Returns 0 on failure. */
JNIEXPORT jlong JNICALL
Java_com_nekroz_meowsub_data_transcribe_WhisperNative_load(JNIEnv *, jobject, jint fd, jboolean flash_attn) {
    whisper_log_set(log_to_logcat, nullptr);
    FILE *file = fdopen(fd, "rb");
    if (file == nullptr) {
        close(fd);
        return 0;
    }
    setvbuf(file, nullptr, _IOFBF, 1 << 20);
    whisper_model_loader loader = {file, read_file, eof_file, close_file}; // closed by whisper
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = flash_attn;
    return reinterpret_cast<jlong>(whisper_init_with_params(&loader, params));
}

JNIEXPORT void JNICALL
Java_com_nekroz_meowsub_data_transcribe_WhisperNative_free(JNIEnv *, jobject, jlong ctx) {
    whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

JNIEXPORT void JNICALL
Java_com_nekroz_meowsub_data_transcribe_WhisperNative_resetAbort(JNIEnv *, jobject) {
    g_abort = false;
}

JNIEXPORT void JNICALL
Java_com_nekroz_meowsub_data_transcribe_WhisperNative_abort(JNIEnv *, jobject) {
    g_abort = true;
}

/**
 * Transcribes 16 kHz mono [samples] as one segment. Returns the text as UTF-8 bytes (decoded on
 * the Kotlin side, which tolerates any invalid sequence), or null when aborted or failed.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_nekroz_meowsub_data_transcribe_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ctx_ptr, jfloatArray samples, jint threads, jstring language,
        jint beam_size, jstring prompt, jint audio_ctx) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    const std::string lang = to_string(env, language);
    const std::string initial_prompt = to_string(env, prompt);

    whisper_full_params p = whisper_full_default_params(
            beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang.c_str();
    p.translate = false;
    p.no_context = true;
    // Timestamp tokens let the decoder end where the speech ends; without them it tends to
    // repeat the sentence until the token limit, above all with a shortened audio_ctx.
    p.no_timestamps = false;
    p.single_segment = true;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.audio_ctx = audio_ctx;
    p.initial_prompt = initial_prompt.empty() ? nullptr : initial_prompt.c_str();
    if (beam_size > 1) p.beam_search.beam_size = beam_size;
    p.abort_callback = [](void *) { return g_abort.load(); };

    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);

    if ((lang.empty() || lang == "auto") && audio_ctx > 0) {
        // whisper.cpp detects the language *before* applying audio_ctx, with the size left over
        // from the previous call (the full 30 s window on the first one: ~8x slower). A call
        // with a fixed language that aborts right before encoding puts the size in place.
        whisper_full_params prime = p;
        prime.language = "en";
        prime.abort_callback = [](void *) { return true; };
        g_quiet = true;
        whisper_full(ctx, prime, data, n); // fails by design
        g_quiet = false;
    }
    const int rc = whisper_full(ctx, p, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0) {
        if (!g_abort) __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_nekroz_meowsub_data_transcribe_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
