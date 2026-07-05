package dev.zyriel.voicejournal.whisper

/**
 * JNI surface to whisper.cpp. Loading whisperjni pulls libwhisper/libggml
 * from the APK's native lib dir via DT_NEEDED.
 *
 * UNVERIFIED ON HARDWARE: compiled for arm64-v8a with NDK r28 (16 KB aligned),
 * never executed on a device.
 */
object WhisperBridge {
    init { System.loadLibrary("whisperjni") }

    external fun initContext(modelPath: String): Long
    external fun fullTranscribe(ctxPtr: Long, samples: FloatArray, nThreads: Int, vadModelPath: String?): String
    external fun freeContext(ctxPtr: Long)
}
