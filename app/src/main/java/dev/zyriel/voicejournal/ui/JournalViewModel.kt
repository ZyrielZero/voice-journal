package dev.zyriel.voicejournal.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zyriel.voicejournal.BuildConfig
import dev.zyriel.voicejournal.audio.AudioPlayer
import dev.zyriel.voicejournal.data.JournalArchive
import dev.zyriel.voicejournal.data.JournalDb
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.data.openOutputStreamTruncating
import dev.zyriel.voicejournal.pipeline.RecordingController
import dev.zyriel.voicejournal.search.KeywordSearch
import dev.zyriel.voicejournal.search.VectorSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * UI-facing state for the journal screen. Deliberately owns nothing that must
 * outlive the Activity: the record/transcribe/insert pipeline lives in
 * [RecordingController], which survives configuration changes, backgrounding,
 * and Activity death. Writes that must survive the screen (delete, edit,
 * import, backfill) go to the controller's [RecordingController.store],
 * which runs them on the process-lived scope. This ViewModel handles what
 * only matters while the screen exists: search, playback, export, and
 * presenting the store's import status.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class JournalViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = RecordingController.get(app)
    private val store = controller.store
    private val dao = JournalDb.get(app).dao()

    val pipeline: StateFlow<RecordingController.PipelineState> = controller.pipeline

    /** True while the launch sweep transcribes audio from an earlier session. */
    val recovering: StateFlow<Boolean> = controller.recovering
    val recorder = controller.recorder
    val player = AudioPlayer()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Entries, filtered by semantic search when a query is active. Typing is
     * debounced (clearing the box is not) because each embed holds the
     * engine mutex around a blocking session.run, and the ranking runs on
     * Default so a Room emission during a query never scores on Main.
     */
    val entries: StateFlow<List<JournalEntry>> =
        combine(
            dao.observeAll(),
            _query.debounce { if (it.isBlank()) 0L else 250L }.distinctUntilChanged(),
        ) { all, q -> all to q }
            .mapLatest { (all, q) ->
                when {
                    q.isBlank() -> all
                    else -> {
                        val qVec = controller.engine?.embedQuery(q)
                        if (qVec != null) VectorSearch.topK(qVec, all, k = 20).map { it.first }
                        else KeywordSearch.rank(q, all, k = 20).map { it.first }
                    }
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Every entry regardless of the query, null until Room's first emission.
     * The edit dialog resolves its entry here so a search re-rank cannot
     * close it mid-edit.
     */
    val allEntries: StateFlow<List<JournalEntry>?> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setQuery(q: String) { _query.value = q }

    fun toggleRecording() {
        player.stop()
        controller.toggleRecording()
    }

    fun play(entry: JournalEntry) {
        val f = File(entry.audioPath)
        if (player.playingFile.value == f) player.stop() else if (f.exists()) player.play(f)
    }

    /** Saved on the controller scope, so leaving the screen cannot drop the edit. */
    fun updateTranscript(entry: JournalEntry, newText: String) {
        store.updateTranscript(entry, newText)
    }

    /** Stops playback of this entry first; the store removes the WAV, then the row. */
    fun delete(entry: JournalEntry) {
        if (player.playingFile.value == File(entry.audioPath)) player.stop()
        store.delete(entry)
    }

    fun dismissError() { controller.dismissError() }

    // ---- Export / import ----

    /** Transfer progress/result for the UI. [inFlight] gates dismissal. */
    data class TransferStatus(val message: String, val inFlight: Boolean)

    private val _exportStatus = MutableStateFlow<TransferStatus?>(null)

    /**
     * One dialog for both directions. Export is screen-scoped and owned
     * here; import lives in the store and outlives this ViewModel, so a
     * reopened screen picks up an import still in flight. Export wins when
     * both are set, which only happens if the user starts one while the
     * other's result is still unread.
     */
    val transferStatus: StateFlow<TransferStatus?> =
        combine(_exportStatus, store.importStatus) { export, import ->
            export ?: import?.let { TransferStatus(it.message, it.inFlight) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun dismissTransferStatus() {
        if (_exportStatus.value != null) {
            if (_exportStatus.value?.inFlight == false) _exportStatus.value = null
            return
        }
        store.dismissImportStatus()
    }

    private fun done(message: String) {
        _exportStatus.value = TransferStatus(message, inFlight = false)
    }

    /**
     * Writes the whole journal to [uri] (from ACTION_CREATE_DOCUMENT).
     * Runs on viewModelScope: transfers are user-initiated with the screen
     * open, and unlike recording there is no OS resource to keep legal.
     * The destination is opened in truncating mode so overwriting a larger
     * file cannot leave its tail behind. Known limitation: backgrounding
     * mid-export abandons a partial zip at the destination; SAF gives no
     * atomic write to fix that from here.
     */
    fun exportTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _exportStatus.value = TransferStatus("Exporting...", inFlight = true)
            try {
                val all = dao.allOnce().sortedByDescending { it.timestampMs }
                getApplication<Application>().contentResolver.openOutputStreamTruncating(uri)?.use { out ->
                    JournalArchive.export(
                        entries = all,
                        resolveAudio = { File(it.audioPath).takeIf { f -> f.isFile } },
                        out = out,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                } ?: error("could not open destination")
                done("Exported ${all.size} ${if (all.size == 1) "entry" else "entries"}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done("Export failed: ${e.message}")
            }
        }
    }

    /**
     * Imports an archive from [uri] (from ACTION_OPEN_DOCUMENT). The store
     * runs it single-flight on the controller scope (staging, two-phase
     * insert and duplicate rules live in JournalImporter) and reports
     * through [transferStatus]; a second request while one runs gets a
     * busy message instead of a parallel import. The store also requests
     * the embedding backfill for the new rows.
     */
    fun importFrom(uri: Uri) {
        store.import(uri)
    }

    override fun onCleared() {
        // The recorder, transcriber, and engine belong to the controller and
        // keep running; only playback is screen-bound.
        player.stop()
    }
}
