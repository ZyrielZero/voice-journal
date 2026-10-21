#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "whisperjni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// whisper.cpp and ggml log model-load details and per-run timings at INFO.
// None of that belongs in a release logcat, and the default sink is stderr,
// which some debuggable setups redirect. Keep only ERROR lines: they carry
// no user data (whisper never sees recording filenames, only samples) and
// they are the one clue left when fullTranscribe returns null.
static void whisper_log_errors_only(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR && text != nullptr) {
        __android_log_write(ANDROID_LOG_ERROR, TAG, text);
    }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    whisper_log_set(whisper_log_errors_only, nullptr);
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_dev_zyriel_voicejournal_whisper_WhisperBridge_initContext(
        JNIEnv *env, jobject, jstring modelPath) {
    if (modelPath == nullptr) return 0;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0; // OutOfMemoryError already pending
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) LOGE("initContext failed");
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Returns the transcript as UTF-8 bytes, or null when transcription failed
 * (no context, the sample array could not be pinned, or whisper_full
 * returned non-zero). An empty array is a real result: whisper_full also
 * returns 0 with no segments when VAD finds no speech. The caller must
 * never conflate the two.
 *
 * Bytes rather than NewStringUTF: whisper emits standard UTF-8, while
 * NewStringUTF expects modified UTF-8 and aborts under CheckJNI on 4-byte
 * sequences. Kotlin decodes the bytes with a replacing decoder.
 */
JNIEXPORT jbyteArray JNICALL
Java_dev_zyriel_voicejournal_whisper_WhisperBridge_fullTranscribe(
        JNIEnv *env, jobject, jlong ctxPtr, jfloatArray samples, jint nThreads,
        jstring vadModelPath, jboolean suppressNst, jfloat temperatureInc) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr || samples == nullptr) return nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "en";
    params.translate = false;
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.print_progress = false;
    params.print_realtime = false;
    params.no_timestamps = true;
    // Decoder knobs, passed explicitly so the Kotlin side owns them.
    // temperature_inc <= 0 disables the fallback ladder (one pass only);
    // whisper's default is 0.2 (up to six passes per segment).
    params.suppress_nst = suppressNst == JNI_TRUE;
    params.temperature_inc = temperatureInc;

    // whisper.cpp's built-in VAD (a bundled Silero graph loaded from a ggml
    // file). When enabled, whisper_full runs detection first and decodes
    // only the speech segments, so silence stops costing encoder time.
    // vad_model_path must outlive whisper_full; hold the chars until after.
    const char *vadPath = nullptr;
    if (vadModelPath != nullptr) {
        vadPath = env->GetStringUTFChars(vadModelPath, nullptr);
        if (vadPath == nullptr) return nullptr; // OutOfMemoryError pending
        if (vadPath[0] != '\0') {
            params.vad = true;
            params.vad_model_path = vadPath;
            params.vad_params = whisper_vad_default_params();
        }
    }

    // Borrow the Java array instead of copying it into a std::vector: a
    // 15-minute clip is ~58 MB of floats, and the copy doubled the peak.
    // GetFloatArrayElements may still copy (ART decides), but it never
    // blocks GC the way GetPrimitiveArrayCritical would for the seconds
    // whisper_full runs. JNI_ABORT: the samples are read-only here.
    jsize n = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) {
        if (vadPath != nullptr) env->ReleaseStringUTFChars(vadModelPath, vadPath);
        return nullptr;
    }

    int rc = whisper_full(ctx, params, pcm, (int) n);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (vadPath != nullptr) env->ReleaseStringUTFChars(vadModelPath, vadPath);
    if (rc != 0) {
        LOGE("whisper_full failed: rc=%d", rc);
        return nullptr;
    }

    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) out += text;
    }
    jbyteArray result = env->NewByteArray((jsize) out.size());
    if (result == nullptr) return nullptr; // OutOfMemoryError pending
    env->SetByteArrayRegion(result, 0, (jsize) out.size(),
                            reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT void JNICALL
Java_dev_zyriel_voicejournal_whisper_WhisperBridge_freeContext(
        JNIEnv *, jobject, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}

}
