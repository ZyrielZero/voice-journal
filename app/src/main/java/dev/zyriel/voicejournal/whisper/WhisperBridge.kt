package dev.zyriel.voicejournal.whisper

/**
 * JNI surface to whisper.cpp. Loading whisperjni pulls libwhisper/libggml
 * from the APK's native lib dir via DT_NEEDED.
 *
 * Hardware-verified (Pixel 10 Pro XL, v0.4.x): transcription runs on-device;
 * see the committed benchmark JSON. The 4-arg fullTranscribe signature with
 * the VAD path compiles for arm64 but the VAD codepath itself has not run on
 * hardware yet.
 */
object WhisperBridge {
    init { System.loadLibrary("whisperjni") }

    external fun initContext(modelPath: String): Long
    external fun fullTranscribe(ctxPtr: Long, samples: FloatArray, nThreads: Int, vadModelPath: String?): String
    external fun freeContext(ctxPtr: Long)
}
