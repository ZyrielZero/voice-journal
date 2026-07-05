#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "whisperjni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_zyriel_voicejournal_whisper_WhisperBridge_initContext(
        JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    LOGI("initContext: %p", (void *) ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_dev_zyriel_voicejournal_whisper_WhisperBridge_fullTranscribe(
        JNIEnv *env, jobject, jlong ctxPtr, jfloatArray samples, jint nThreads,
        jstring vadModelPath) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "en";
    params.translate = false;
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.print_progress = false;
    params.print_realtime = false;
    params.no_timestamps = true;

    // whisper.cpp's built-in VAD (a bundled Silero graph loaded from a ggml
    // file). When enabled, whisper_full runs detection first and decodes
    // only the speech segments, so silence stops costing encoder time.
    // vad_model_path must outlive whisper_full; hold the chars until after.
    const char *vadPath = nullptr;
    if (vadModelPath != nullptr) {
        vadPath = env->GetStringUTFChars(vadModelPath, nullptr);
        if (vadPath != nullptr && vadPath[0] != '\0') {
            params.vad = true;
            params.vad_model_path = vadPath;
            params.vad_params = whisper_vad_default_params();
        }
    }

    int rc = whisper_full(ctx, params, pcm.data(), (int) pcm.size());
    if (vadPath != nullptr) env->ReleaseStringUTFChars(vadModelPath, vadPath);
    if (rc != 0) {
        return env->NewStringUTF("");
    }

    std::string out;
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        out += whisper_full_get_segment_text(ctx, i);
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_dev_zyriel_voicejournal_whisper_WhisperBridge_freeContext(
        JNIEnv *, jobject, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) whisper_free(ctx);
}

}
