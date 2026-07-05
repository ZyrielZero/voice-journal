package dev.zyriel.voicejournal.pipeline

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.zyriel.voicejournal.audio.AudioRecorder
import dev.zyriel.voicejournal.audio.OrphanSweep
import dev.zyriel.voicejournal.data.JournalDb
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import dev.zyriel.voicejournal.search.WordPieceTokenizer
import dev.zyriel.voicejournal.whisper.Transcriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * UNVERIFIED ON HARDWARE for the service-interaction paths. The JVM-testable
 * pieces (WavRepair, OrphanSweep, WavWriter/Reader) carry the unit tests;
 * this class is the thin Android glue between them and must be validated on
 * a device: record with screen off, kill from recents mid-recording and
 * mid-transcription, relaunch, verify recovery.
 */
class RecordingController private constructor(private val app: Context) {

    sealed interface PipelineState {
        data object Idle : PipelineState
        data object Recording : PipelineState
        data object Transcribing : PipelineState
        data class Failed(val message: String) : PipelineState
    }

    /** Survives configuration changes and Activity death; dies with the process. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val recorder = AudioRecorder(scope)
    private val dao = JournalDb.get(app).dao()
    private val transcriber = Transcriber(app)
    private val dir = File(app.filesDir, "recordings").apply { mkdirs() }

    private val _pipeline = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipeline: StateFlow<PipelineState> = _pipeline.asStateFlow()

    /** Null until initialized; stays null if init fails, and search falls back to keywords. */
    @Volatile var engine: OnnxEmbeddingEngine? = null
        private set

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
        // A capture failure (mic read error, device revoked the stream)
        // breaks the recorder's loop but used to be observed by nothing:
        // the pipeline sat in Recording with a frozen timer while no audio
        // flowed. Salvage what was captured — the WAV is finalized by the
        // loop's use{} — and run it through the normal stop path, which
        // also winds down the service and notification.
        scope.launch {
            recorder.state.collect { s ->
                if (s is AudioRecorder.State.Error && _pipeline.value is PipelineState.Recording) {
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
            sweepOrphans()
            backfillEmbeddings()
        }
    }

    /**
     * Recovers WAVs stranded by an earlier process death. Runs once per
     * process. Per-file failures are captured in the report and logged, not
     * thrown and not swallowed: a bad orphan stays on disk for the next
     * sweep and cannot shadow the recoverable ones behind it. The active
     * path is a supplier so a recording started mid-sweep is skipped, not
     * "repaired" while it's being written.
     */
    private suspend fun sweepOrphans() {
        val referenced = dao.allOnce().map { it.audioPath }.toSet()
        val report = OrphanSweep.sweep(dir, referenced, { activeFile?.absolutePath }) { file ->
            // Recovered audio gets a real transcript; timestamp comes from the
            // file itself so the entry lands where the user actually spoke it.
            kotlinx.coroutines.runBlocking { insertTranscribed(file, file.lastModified()) }
        }
        if (report.failed.isNotEmpty()) Log.w(TAG, report.oneLine())
        else Log.i(TAG, report.oneLine())
    }

    /** Embeds entries that have no vector or a vector from a different model. */
    private suspend fun backfillEmbeddings() {
        val e = engine ?: return
        for (entry in dao.needingEmbedding(OnnxEmbeddingEngine.MODEL_TAG)) {
            val v = e.embed(entry.transcript) ?: continue
            dao.update(entry.copy(embedding = v, embeddingModel = OnnxEmbeddingEngine.MODEL_TAG))
        }
    }

    /**
     * Starts a new recording and the foreground service that keeps it legal
     * once the screen goes off. Must be called while the app is visible:
     * microphone is a while-in-use type, so a background start would get a
     * service with no mic access. The UI's Rec tap satisfies this by nature.
     */
    fun startRecording() {
        if (_pipeline.value !is PipelineState.Idle && _pipeline.value !is PipelineState.Failed) return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(dir, "entry_$name.wav")
        activeFile = f
        _pipeline.value = PipelineState.Recording
        recorder.start(f)
        ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
    }

    /** Stops recording and runs the rest of the pipeline on the controller scope. */
    fun stopAndTranscribe() {
        if (_pipeline.value !is PipelineState.Recording) return
        _pipeline.value = PipelineState.Transcribing
        val file = activeFile ?: run { _pipeline.value = PipelineState.Idle; return }
        scope.launch {
            try {
                recorder.stopAndWait()   // WAV header must be finalized first
                insertTranscribed(file, System.currentTimeMillis())
                _pipeline.value = PipelineState.Idle
            } catch (e: Exception) {
                _pipeline.value = PipelineState.Failed(e.message ?: "transcription failed")
            } finally {
                activeFile = null
            }
        }
    }

    private suspend fun insertTranscribed(file: File, timestampMs: Long) {
        val text = transcriber.transcribe(file)
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
     * Public entry point for the import path, which inserts entries with
     * null embeddings and hands vectorization to the same code that
     * covers first-launch backfill.
     */
    fun requestBackfill() {
        scope.launch { backfillEmbeddings() }
    }

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
