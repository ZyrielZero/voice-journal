package dev.zyriel.voicejournal.pipeline

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.zyriel.voicejournal.audio.AudioRecorder
import dev.zyriel.voicejournal.audio.OrphanSweep
import dev.zyriel.voicejournal.audio.WavWriter
import dev.zyriel.voicejournal.data.JournalDb
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.data.JournalStore
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import dev.zyriel.voicejournal.search.WordPieceTokenizer
import dev.zyriel.voicejournal.whisper.ModelUnavailableException
import dev.zyriel.voicejournal.whisper.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-scoped owner of the record -> transcribe -> embed -> insert pipeline.
 *
 * This used to live in JournalViewModel on viewModelScope, which dies with
 * the Activity: backgrounding the app mid-recording or mid-transcription
 * killed the pipeline and orphaned the WAV. The controller's scope lives as
 * long as the process; [RecordingService] keeps the process alive and
 * microphone-legal while the screen is off. If the process dies anyway,
 * [sweepOrphans] recovers on next launch (NFR-06).
 *
 * Service-interaction paths hardware-verified (Pixel 10 Pro XL): screen-off
 * recording, kill mid-recording, kill mid-transcription, relaunch recovery
 * all passed. The JVM-testable pieces (WavRepair, OrphanSweep, WavWriter/
 * Reader, the stop compare-and-set) carry the unit tests. Newer glue is NOT
 * yet device-verified: the auto-stop collector, the capture-error salvage
 * path, the recovery foreground service and audio-focus stop.
 */
class RecordingController private constructor(private val app: Context) {

    sealed interface PipelineState {
        data object Idle : PipelineState
        data object Recording : PipelineState
        data object Transcribing : PipelineState
        data class Failed(val message: String) : PipelineState
    }

    /**
     * Survives configuration changes and Activity death; dies with the
     * process. There is no Application-level uncaught handler, so anything
     * that escapes a coroutine here would kill the process; the handler logs
     * it instead. Exceptions are still caught where they happen; this is
     * the backstop, not the policy.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught in controller scope", e) }
    )

    val recorder = AudioRecorder(scope)
    private val dao = JournalDb.get(app).dao()
    private val transcriber = Transcriber(app)
    private val dir = File(app.filesDir, "recordings").apply { mkdirs() }

    private val _pipeline = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipeline: StateFlow<PipelineState> = _pipeline.asStateFlow()

    /**
     * True while the launch sweep is transcribing an orphan. Separate from
     * [pipeline] because recovery and a new recording can overlap: Rec stays
     * usable, and the service stays up while either is in flight.
     */
    private val _recovering = MutableStateFlow(false)
    val recovering: StateFlow<Boolean> = _recovering.asStateFlow()

    /**
     * Orphans moved to quarantine after repeated failed recoveries. Kept on
     * disk, never deleted; read once after each launch sweep.
     */
    private val _quarantined = MutableStateFlow(0)
    val quarantined: StateFlow<Int> = _quarantined.asStateFlow()

    /** Null until initialized; stays null if init fails, and search falls back to keywords. */
    @Volatile var engine: OnnxEmbeddingEngine? = null
        private set

    /** Process-lived owner of every write that must outlive the UI (contract C3). */
    val store = JournalStore(app, dao, scope) { engine }

    @Volatile private var activeFile: File? = null

    init {
        // Auto-stop at the transcription cap so no recording can outgrow
        // what the pipeline can hold in memory (Transcriber.MAX_CLIP_SECONDS).
        // Lives here, not in AudioRecorder: the recorder is a dumb capture
        // loop and the cap is pipeline policy.
        scope.launch {
            recorder.elapsedMs.collect { ms ->
                if (_pipeline.value is PipelineState.Recording &&
                    ms >= Transcriber.MAX_CLIP_SECONDS * 1000L
                ) {
                    stopAndTranscribe()
                }
            }
        }
        // A capture failure (mic read error, disk full, device revoked the
        // stream) breaks the recorder's loop. Salvage what was captured (the
        // WAV is finalized by the loop's use{}) through the normal stop
        // path, which also winds down the service and notification. Both
        // flows are combined so the order of the two transitions does not
        // matter: an Error that lands before the pipeline flips to
        // Recording is still seen.
        scope.launch {
            combine(recorder.state, _pipeline) { s, p -> s to p }.collect { (s, p) ->
                if (s is AudioRecorder.State.Error && p is PipelineState.Recording) {
                    Log.w(TAG, "capture failed mid-recording, salvaging partial audio: ${s.message}")
                    stopAndTranscribe()
                }
            }
        }
        scope.launch {
            engine = runCatching {
                OnnxEmbeddingEngine(
                    modelBytes = app.assets.open("models/bge_small_en_v15_q8.onnx").use { it.readBytes() },
                    vocab = app.assets.open("models/bge_vocab.txt").use { WordPieceTokenizer(it) },
                )
            }.getOrNull()
            try {
                sweepOrphans()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The database itself is unusable (a schema slip): every
                // reference check would fail, so nothing was touched.
                Log.e(TAG, "orphan sweep aborted", e)
            }
            store.requestBackfill()
        }
    }

    /**
     * Recovers WAVs stranded by an earlier process death. Runs once per
     * process. Per-file failures are captured in the report and logged, not
     * thrown and not swallowed: a bad orphan stays on disk for the next
     * sweep (until OrphanSweep quarantines it) and cannot shadow the
     * recoverable ones behind it. The reference check and the active path
     * are suppliers, asked per file, so a recording started or an import
     * inserted mid-sweep is skipped, not recovered a second time.
     *
     * While an orphan is being transcribed, [recovering] is true and, when
     * the app is in the foreground with the mic granted, the foreground
     * service is held, so a backgrounded app is not frozen or killed
     * mid-recovery. A live recording's transcription still queues
     * behind the orphan on the Transcriber mutex.
     */
    private suspend fun sweepOrphans() {
        val report = try {
            OrphanSweep.sweep(
                dir = dir,
                isReferenced = { path -> dao.countByAudioPath(path) > 0 },
                activePath = { activeFile?.absolutePath },
                // An import running now owns its .importing WAVs; leave them.
                importInFlight = { store.isImporting },
            ) { file ->
                beginRecovery()
                // Recovered audio gets a real transcript; timestamp comes from
                // the file itself so the entry lands where the user spoke it.
                try {
                    insertTranscribed(file, file.lastModified())
                } catch (e: ModelUnavailableException) {
                    // Every orphan would fail the same way; stop, and do not
                    // let a broken model walk the user's audio into quarantine.
                    throw OrphanSweep.Abort(e)
                }
            }
        } finally {
            _recovering.value = false
        }
        if (report.failed.isNotEmpty() || report.aborted != null) Log.w(TAG, report.oneLine())
        else Log.i(TAG, report.oneLine())
        for (q in report.quarantined) Log.i(TAG, "quarantined after repeated failures: ${q.name}")
        _quarantined.value = OrphanSweep.quarantinedCount(dir)
    }

    private fun beginRecovery() {
        if (_recovering.value) return
        _recovering.value = true
        // The service is a microphone-type FGS. Starting one for recovery is
        // only legal (and only guaranteed to reach startForeground, which a
        // startForegroundService must) while the mic is granted and the app
        // is in the foreground. Otherwise recover without it: the work still
        // runs, just unprotected from the freezer, and an interrupted file
        // is retried by the next sweep.
        if (canHostRecoveryService()) startService()
        else Log.i(TAG, "recovering without the foreground service")
    }

    private fun canHostRecoveryService(): Boolean {
        val mic = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!mic) return false
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * Starts a new recording and the foreground service that keeps it legal
     * once the screen goes off. Must be called while the app is visible:
     * microphone is a while-in-use type, so a background start would get a
     * service with no mic access. The UI's Rec tap satisfies this by nature.
     *
     * The pipeline flips to Recording and the service starts only after the
     * recorder has actually started; a synchronous failure lands in Failed
     * with the recorder's own reason.
     */
    fun startRecording() {
        if (_pipeline.value !is PipelineState.Idle && _pipeline.value !is PipelineState.Failed) return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "entry_$name.wav")
        if (!recorder.start(f)) {
            val reason = (recorder.state.value as? AudioRecorder.State.Error)?.message
            _pipeline.value = PipelineState.Failed(reason ?: "Could not start recording")
            return
        }
        activeFile = f
        _pipeline.value = PipelineState.Recording
        startService()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
        } catch (e: Exception) {
            // Background start refused (ForegroundServiceStartNotAllowedException)
            // or similar. The work still runs; it just is not protected from
            // the freezer while the app is in the background.
            Log.w(TAG, "could not start the foreground service: ${e.message}")
        }
    }

    /**
     * Stops recording and runs the rest of the pipeline on the controller
     * scope. Safe to call from any thread and any number of times: the
     * auto-stop collector, the salvage collector, the FAB, the notification
     * Stop and audio-focus loss can all race here, and only the caller that
     * wins the Recording -> Transcribing compare-and-set proceeds.
     */
    fun stopAndTranscribe() {
        if (!_pipeline.tryBeginStop()) return
        val file = activeFile ?: run { _pipeline.value = PipelineState.Idle; return }
        scope.launch {
            try {
                recorder.stopAndWait()   // WAV header must be finalized first
                val captureError = (recorder.state.value as? AudioRecorder.State.Error)?.message
                if (captureError != null && file.length() <= WavWriter.HEADER_SIZE) {
                    // Nothing was captured; there is nothing to salvage.
                    file.delete()
                    finish(PipelineState.Failed("Recording failed: $captureError"))
                    return@launch
                }
                insertTranscribed(file, System.currentTimeMillis())
                finish(PipelineState.Idle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finish(PipelineState.Failed(e.message ?: "transcription failed"))
            } finally {
                // An Error (OOM) is deliberately not caught above, but the
                // pipeline must not be left showing Transcribing forever.
                if (_pipeline.value is PipelineState.Transcribing) {
                    finish(PipelineState.Failed("transcription failed"))
                }
            }
        }
    }

    /**
     * Publishes a terminal state, clearing [activeFile] first. The other
     * order lets a Rec tap that sees Idle start a new recording and set
     * [activeFile], which a late clear would then wipe, exposing the live
     * file to the orphan sweep.
     */
    private fun finish(state: PipelineState) {
        activeFile = null
        _pipeline.value = state
    }

    /**
     * Transcribes [file] and inserts its entry. A native transcription
     * failure (contract C1: null) throws instead of inserting, so the WAV
     * stays unreferenced and the next launch's sweep retries it. A model
     * that cannot load surfaces as [ModelUnavailableException]. Real
     * silence ("") is still saved, as "(no speech detected)".
     */
    private suspend fun insertTranscribed(file: File, timestampMs: Long) {
        val text: String? = transcriber.transcribeStrict(file)
        if (text == null) {
            throw IllegalStateException("Transcription failed. The audio is kept and will be retried on next launch.")
        }
        val finalText = text.ifBlank { "(no speech detected)" }
        val vec = engine?.embed(finalText)
        dao.insert(JournalEntry(
            transcript = finalText,
            timestampMs = timestampMs,
            audioPath = file.absolutePath,
            embedding = vec,
            embeddingModel = if (vec != null) OnnxEmbeddingEngine.MODEL_TAG else null,
        ))
    }

    fun toggleRecording() {
        when (_pipeline.value) {
            is PipelineState.Recording -> stopAndTranscribe()
            is PipelineState.Transcribing -> Unit // wait
            else -> startRecording()
        }
    }

    /**
     * Embeds anything sitting in the DB without a current-model vector.
     * Kept for existing callers; delegates to [JournalStore.requestBackfill],
     * which is single-flight and never overwrites an edited transcript.
     */
    fun requestBackfill() = store.requestBackfill()

    fun dismissError() {
        if (_pipeline.value is PipelineState.Failed) _pipeline.value = PipelineState.Idle
    }

    companion object {
        private const val TAG = "RecordingController"

        @Volatile private var instance: RecordingController? = null

        fun get(context: Context): RecordingController =
            instance ?: synchronized(this) {
                instance ?: RecordingController(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Recording -> Transcribing as one atomic step. Returns true for exactly one
 * of any number of concurrent callers; everyone else sees false and backs off.
 */
internal fun MutableStateFlow<RecordingController.PipelineState>.tryBeginStop(): Boolean =
    compareAndSet(RecordingController.PipelineState.Recording, RecordingController.PipelineState.Transcribing)
