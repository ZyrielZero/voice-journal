package dev.zyriel.voicejournal.whisper

import android.content.Context
import dev.zyriel.voicejournal.audio.WavReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the whisper context lifecycle. Model is copied from assets to the
 * files dir on first use (whisper.cpp needs a real file path). One context,
 * serialized transcriptions; whisper_full is not reentrant per context.
 */
class Transcriber(private val context: Context) {

    companion object {
        private const val ASSET = "models/ggml-base.en-q5_1.bin"
    }

    private var ctxPtr: Long = 0
    private val mutex = Mutex()

    private suspend fun ensureContext(): Long = mutex.withLock {
        if (ctxPtr != 0L) return ctxPtr
        val modelFile = File(context.filesDir, "ggml-base.en-q5_1.bin")
        if (!modelFile.exists() || modelFile.length() == 0L) {
            withContext(Dispatchers.IO) {
                context.assets.open(ASSET).use { input ->
                    modelFile.outputStream().use { input.copyTo(it) }
                }
            }
        }
        ctxPtr = withContext(Dispatchers.Default) { WhisperBridge.initContext(modelFile.absolutePath) }
        check(ctxPtr != 0L) { "whisper context init failed" }
        return ctxPtr
    }

    /** Blocking-heavy; runs whisper on Dispatchers.Default. Returns trimmed transcript. */
    suspend fun transcribe(wav: File): String {
        val ctx = ensureContext()
        val samples = withContext(Dispatchers.IO) { WavReader.readFloatPcm(wav) }
        if (samples.isEmpty()) return ""
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                WhisperBridge.fullTranscribe(ctx, samples, threads).trim()
            }
        }
    }

    fun release() {
        if (ctxPtr != 0L) { WhisperBridge.freeContext(ctxPtr); ctxPtr = 0 }
    }
}
