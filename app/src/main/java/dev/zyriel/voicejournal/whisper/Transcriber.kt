package dev.zyriel.voicejournal.whisper

import android.content.Context
import android.util.Log
import dev.zyriel.voicejournal.BuildConfig
import dev.zyriel.voicejournal.audio.AtomicMaterialize
import dev.zyriel.voicejournal.audio.WavReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The whisper context could not be created (model missing, truncated, or
 * rejected by whisper.cpp). Not the audio's fault: callers that count
 * per-file failures must not count this one.
 */
class ModelUnavailableException(message: String) : IllegalStateException(message)

/**
 * Owns the whisper context lifecycle. Models are copied from assets to the
 * files dir on first use (whisper.cpp needs a real file path). One context,
 * serialized transcriptions; whisper_full is not reentrant per context.
 */
class Transcriber(private val context: Context) {

    companion object {
        private const val TAG = "Transcriber"
        private const val MODEL_FILE = "ggml-base.en-q5_1.bin"
        private const val VAD_FILE = "ggml-silero-v5.1.2.bin"
        private const val ASSET = "models/$MODEL_FILE"
        internal const val VAD_ASSET = "models/$VAD_FILE"

        /**
         * Longest entry the pipeline will record and transcribe. Bounds the
         * transcription heap: whisper needs the whole clip as one FloatArray
         * (4 bytes/sample at 16 kHz = ~3.7 MB/min), so unbounded recordings
         * OOM the pipeline — and worse, the orphan sweep then retries the
         * same OOM on every launch. 15 min = ~58 MB of floats. 30 min
         * (~115 MB) was too close to the 192 MB default heap once the 33 MB
         * ONNX model and a second resident clip were counted.
         */
        const val MAX_CLIP_SECONDS = 15 * 60

        /**
         * Reader-side guard, slightly above the auto-stop cap: the recorder
         * stops on an elapsed-time check between buffer reads, so the file
         * can legitimately run a few chunks past MAX_CLIP_SECONDS. Only
         * files that never went through the cap (pre-cap orphans, imported
         * audio fed to the bench) trip this. About 61 MB of floats.
         */
        internal const val MAX_READ_SAMPLES = (MAX_CLIP_SECONDS + 60) * 16_000

        /**
         * Suppress whisper's non-speech tokens (brackets, parentheses, music
         * notes and similar; see non_speech_tokens in whisper.cpp). This is
         * OpenAI's reference default (suppress_tokens="-1"), which the
         * published base.en WER was measured with; whisper.cpp ships it off.
         * Off, base.en can emit "[BLANK_AUDIO]", "(music)" or "♪" on
         * silence and noise, which would be saved as the entry's transcript
         * instead of the empty result the pipeline maps to "no speech".
         * Not yet re-measured on device: re-run the accuracy suite.
         */
        const val DEFAULT_SUPPRESS_NST = true

        /**
         * Threads whisper computes on: one. ggml's worker threads
         * synchronise by spinning, and base.en's matrices are small enough
         * that the waiting outweighs the shared work, so more threads were
         * slower, and now and then one run stalled for many times its
         * normal length. Measured on a Pixel 11 Pro XL (Tensor G6, 7 cores,
         * VAD on) with a 60 s LibriSpeech clip: 1 thread 6.0 to 7.0 s,
         * 2 threads 5.3 to 7.1 s with one run of 65 s, 3 threads 10 s,
         * 4 threads 12 to 15 s, 6 threads 13 to 18 s (the old value, and
         * 27 to 45 s once the phone was warm). A single thread never
         * stalled: the accuracy suite's three runs per row agreed to within
         * a few percent. Not yet measured on a slower phone, where the
         * balance may differ.
         */
        const val WHISPER_THREADS = 1

        /**
         * whisper's own default. Each segment that fails the entropy or
         * logprob thresholds is re-decoded at T += 0.2, up to six passes.
         * Disabling it (any value <= 0) is the review's suggested
         * experiment, but it can raise WER on hard clips, so the default
         * stays until the accuracy suite says otherwise.
         */
        const val DEFAULT_TEMPERATURE_INC = 0.2f

        /**
         * Process-wide, not per instance: the bench and accuracy suites build
         * their own Transcriber, and every instance materializes into the
         * same filesDir paths. Also guards the one-time stale-model sweep,
         * which must never run while a copy is in flight.
         */
        private val materializeLock = Mutex()
        private var staleSwept = false // guarded by materializeLock

        private fun isManagedModel(name: String) = name.startsWith("ggml-") && name.endsWith(".bin")
    }

    /**
     * VAD silence trim via whisper.cpp's built-in Silero graph. Enabled by
     * default on committed device numbers (vj-accuracy-ce5f560.json,
     * vj-benchmark-ce5f560.json): zero deletions in every accuracy row —
     * the trim eats no words — with corpus WER improving 6.31% -> 4.50%
     * (a small sample: 222 reference words). Continuous speech pays about
     * 1.5x to 1.7x in latency for it (60 s clip: 6.2 s off, 9.3 s on).
     * The saving on pause-heavy speech has only been measured on a
     * synthetic tone so far and is not a real-speech number; the
     * `acc.speech_gaps` bench rows measure it and await a device run.
     */
    @Volatile var vadEnabled: Boolean = true

    /** See [DEFAULT_SUPPRESS_NST]. Read at each transcription. */
    @Volatile var suppressNst: Boolean = DEFAULT_SUPPRESS_NST

    /** See [DEFAULT_TEMPERATURE_INC]. Read at each transcription. */
    @Volatile var temperatureInc: Float = DEFAULT_TEMPERATURE_INC

    init {
        // Before anything touches WhisperBridge, whose init loads the library.
        FastLibMarker.buildId = BuildConfig.VERSION_CODE.toString()
        FastLibMarker.dir = context.filesDir
    }

    // Both guarded by mutex.
    private var ctxPtr: Long = 0
    private var vadPath: String? = null
    private var vadResolved = false
    private val mutex = Mutex()

    /** Copies [asset] to filesDir/[name] if needed, validating its length. */
    private suspend fun materialize(name: String, asset: String): File = withContext(Dispatchers.IO) {
        materializeLock.withLock {
            if (!staleSwept) {
                staleSwept = true
                val keep = setOf(MODEL_FILE, VAD_FILE)
                val failed = AtomicMaterialize.deleteStaleSiblings(context.filesDir, keep, ::isManagedModel)
                if (failed.isNotEmpty()) Log.w(TAG, "could not delete stale models: $failed")
            }
            // Assets under noCompress ("bin") are stored, so openFd works and
            // declaredLength is the exact size to validate the copy against.
            val expected = runCatching {
                context.assets.openFd(asset).use { it.declaredLength }
            }.getOrDefault(AtomicMaterialize.UNKNOWN_LENGTH)
            AtomicMaterialize.ensure(File(context.filesDir, name), expected) {
                context.assets.open(asset)
            }
        }
    }

    /**
     * Returns the native context, creating it on first use, or 0 when the
     * model cannot be materialized or loaded. Caller holds [mutex].
     */
    private suspend fun ensureContextLocked(): Long {
        if (ctxPtr != 0L) return ctxPtr
        // Atomic copy: a process death mid-write must never leave a
        // truncated model that passes existence checks and bricks
        // transcription on every subsequent launch.
        val modelFile = try {
            materialize(MODEL_FILE, ASSET)
        } catch (e: IOException) {
            Log.e(TAG, "model materialization failed", e)
            return 0L
        }
        ctxPtr = withContext(Dispatchers.Default) { WhisperBridge.initContext(modelFile.absolutePath) }
        if (ctxPtr == 0L) Log.e(TAG, "whisper context init failed")
        return ctxPtr
    }

    /**
     * Path to the VAD model file, materialized from assets on first use.
     * Null when the asset isn't bundled (older checkouts that haven't
     * re-run fetch-models), in which case VAD is silently unavailable and
     * transcription behaves exactly as before. Caller holds [mutex].
     */
    private suspend fun vadPathLocked(): String? {
        if (!vadResolved) {
            vadPath = runCatching { materialize(VAD_FILE, VAD_ASSET).absolutePath }.getOrNull()
            vadResolved = true
        }
        return vadPath
    }

    /**
     * Transcribes [wav] on Dispatchers.Default. The WAV is read inside the
     * mutex, so at most one clip's samples are resident per Transcriber.
     *
     * @param useVad explicit VAD override, so the benchmark suite can
     *   measure both paths regardless of the default.
     * @return the trimmed transcript, possibly "" for real silence, or null
     *   when native transcription failed (no context, or whisper_full
     *   returned non-zero). Callers must not save null as an empty entry.
     * @throws IllegalArgumentException when the WAV is unreadable or longer
     *   than the [MAX_READ_SAMPLES] cap.
     */
    suspend fun transcribe(wav: File, useVad: Boolean = vadEnabled): String? =
        try {
            transcribeStrict(wav, useVad)
        } catch (e: ModelUnavailableException) {
            null
        }

    /**
     * [transcribe], but a missing context is reported as
     * [ModelUnavailableException] instead of folded into null, so a caller
     * can tell "the model is broken" (retry later, blame nothing) from
     * "this file failed" (null, or IllegalArgumentException).
     */
    suspend fun transcribeStrict(wav: File, useVad: Boolean = vadEnabled): String? = mutex.withLock {
        val ctx = ensureContextLocked()
        if (ctx == 0L) throw ModelUnavailableException(
            "The transcription model could not be loaded. The audio is kept and will be retried on next launch."
        )
        val samples = withContext(Dispatchers.IO) {
            try {
                WavReader.readFloatPcm(wav, MAX_READ_SAMPLES)
            } catch (e: IOException) {
                throw IllegalArgumentException("unreadable WAV: ${e.message}", e)
            }
        }
        if (samples.isEmpty()) return@withLock ""
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, WHISPER_THREADS)
        val vad = if (useVad) vadPathLocked() else null
        val nst = suppressNst
        val tInc = temperatureInc
        val started = System.nanoTime()
        val bytes = withContext(Dispatchers.Default) {
            WhisperBridge.transcribe(ctx, samples, threads, vad, nst, tInc)
        }
        // Durations only, never content.
        Log.i(TAG, "transcribed ${samples.size / 16_000} s of audio in " +
            "${(System.nanoTime() - started) / 1_000_000} ms ($threads threads, vad ${vad != null})")
        bytes?.toString(Charsets.UTF_8)?.trim()
    }

    /**
     * Frees the native context. Suspends for the same mutex transcription
     * holds: freeing mid-transcribe is a native use-after-free, and a
     * lifecycle method on a class whose whole design is "one context,
     * serialized" doesn't get to skip the serialization.
     */
    suspend fun release() = mutex.withLock {
        if (ctxPtr != 0L) { WhisperBridge.freeContext(ctxPtr); ctxPtr = 0 }
    }
}
