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
        private const val VAD_ASSET = "models/ggml-silero-v5.1.2.bin"

        /**
         * Longest entry the pipeline will record and transcribe. Bounds the
         * transcription heap: whisper needs the whole clip as one FloatArray
         * (4 bytes/sample at 16 kHz = ~3.7 MB/min), so unbounded recordings
         * OOM the pipeline — and worse, the orphan sweep then retries the
         * same OOM on every launch. 30 min = ~115 MB of floats, safely
         * inside any modern heap while being far longer than a journal
         * entry has business being.
         */
        const val MAX_CLIP_SECONDS = 30 * 60

        /**
         * Reader-side guard, slightly above the auto-stop cap: the recorder
         * stops on an elapsed-time check between buffer reads, so the file
         * can legitimately run a few chunks past MAX_CLIP_SECONDS. Only
         * files that never went through the cap (pre-cap orphans, imported
         * audio fed to the bench) trip this.
         */
        internal const val MAX_READ_SAMPLES = (MAX_CLIP_SECONDS + 60) * 16_000
    }

    /**
     * Opt-in VAD silence trim via whisper.cpp's built-in Silero graph.
     * DEFAULT OFF: enabling this app-wide is a performance/accuracy claim,
     * and that claim needs committed before/after benchmark JSON from real
     * hardware first (the benchmark suite measures both paths). Turning it
     * on is a one-line change here once the numbers justify it.
     */
    @Volatile var vadEnabled: Boolean = false

    private var ctxPtr: Long = 0
    private val mutex = Mutex()

    /**
     * Path to the VAD model file, materialized from assets on first use.
     * Null when the asset isn't bundled (older checkouts that haven't
     * re-run fetch-models), in which case VAD is silently unavailable and
     * transcription behaves exactly as before.
     */
    private suspend fun vadModelPath(): String? {
        val f = File(context.filesDir, "ggml-silero-v5.1.2.bin")
        if (f.exists() && f.length() > 0L) return f.absolutePath
        return withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(VAD_ASSET).use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
                f.absolutePath
            }.getOrElse { f.delete(); null }
        }
    }

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
    suspend fun transcribe(wav: File): String = transcribe(wav, useVad = vadEnabled)

    /**
     * Same as [transcribe] but with an explicit VAD override, so the
     * benchmark suite can measure both paths regardless of the default.
     */
    suspend fun transcribe(wav: File, useVad: Boolean): String {
        val ctx = ensureContext()
        val samples = withContext(Dispatchers.IO) { WavReader.readFloatPcm(wav, MAX_READ_SAMPLES) }
        if (samples.isEmpty()) return ""
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val vadPath = if (useVad) vadModelPath() else null
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                WhisperBridge.fullTranscribe(ctx, samples, threads, vadPath).trim()
            }
        }
    }

    fun release() {
        if (ctxPtr != 0L) { WhisperBridge.freeContext(ctxPtr); ctxPtr = 0 }
    }
}
