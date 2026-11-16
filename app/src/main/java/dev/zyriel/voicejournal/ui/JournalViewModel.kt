package dev.zyriel.voicejournal.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zyriel.voicejournal.BuildConfig
import dev.zyriel.voicejournal.R
import dev.zyriel.voicejournal.audio.AudioPlayer
import dev.zyriel.voicejournal.data.ArchiveCrypto
import dev.zyriel.voicejournal.data.JournalArchive
import dev.zyriel.voicejournal.data.JournalDb
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.data.openOutputStreamTruncating
import dev.zyriel.voicejournal.pipeline.RecordingController
import dev.zyriel.voicejournal.search.HybridSearch
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

    /** Recordings set aside after repeated failed transcriptions. */
    val quarantined: StateFlow<Int> = controller.quarantined

    fun retryQuarantined() = controller.retryQuarantined()

    fun deleteQuarantined() = controller.deleteQuarantined()
    val recorder = controller.recorder
    val player = AudioPlayer()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Entries, filtered by [HybridSearch] (meaning plus words) when a query
     * is active; an empty result is a real "nothing matches". Typing is
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
                        // Null vector (engine unavailable) means keywords only.
                        val qVec = controller.engine?.embedQuery(q)
                        HybridSearch.rank(q, qVec, all, k = 20).map { it.entry }
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

    /**
     * Transfer progress/result for the UI. [inFlight] gates dismissal.
     * [passwordFor] asks the UI for the password of the encrypted archive
     * at that Uri; [wrongPassword] says the last one did not open it.
     */
    data class TransferStatus(
        val message: String,
        val inFlight: Boolean,
        val passwordFor: Uri? = null,
        val wrongPassword: Boolean = false,
    )

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
            export ?: import?.let { TransferStatus(it.message, it.inFlight, it.passwordFor, it.wrongPassword) }
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

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    /**
     * The password for the export the user is about to pick a destination
     * for. Held here, not in saved UI state: a ViewModel survives rotation
     * while the system file picker is open, and a password must never be
     * written into a saved-instance bundle. It does not survive process
     * death; [exportTo] then refuses instead of exporting unencrypted.
     */
    private var pendingExportPassword: CharArray? = null

    /** Sets (or with null clears) the password for the next [exportTo]. */
    fun setExportPassword(password: CharArray?) {
        pendingExportPassword?.fill('\u0000')
        pendingExportPassword = password
    }

    /**
     * Writes the whole journal to [uri] (from ACTION_CREATE_DOCUMENT),
     * encrypted with the pending password when [encrypted] is true.
     * Recordings held in quarantine have no entry yet; they are exported
     * as entries with a placeholder transcript so their audio is not left
     * behind. Runs on viewModelScope: transfers are user-initiated with the
     * screen open, and unlike recording there is no OS resource to keep
     * legal. The destination is opened in truncating mode so overwriting a
     * larger file cannot leave its tail behind. Known limitation:
     * backgrounding mid-export abandons a partial file at the destination;
     * SAF gives no atomic write to fix that from here.
     */
    fun exportTo(uri: Uri, encrypted: Boolean) {
        val password = pendingExportPassword
        pendingExportPassword = null
        if (encrypted && password == null) {
            // The process was restarted behind the file picker. Writing the
            // journal in the clear here would be the opposite of what was asked.
            done(str(R.string.export_password_lost))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _exportStatus.value = TransferStatus(str(R.string.export_in_progress), inFlight = true)
            try {
                val placeholder = str(R.string.quarantined_transcript)
                val unsaved = controller.quarantinedFiles().map { f ->
                    JournalEntry(transcript = placeholder, timestampMs = f.lastModified(), audioPath = f.absolutePath)
                }
                val all = (dao.allOnce() + unsaved).sortedByDescending { it.timestampMs }
                getApplication<Application>().contentResolver.openOutputStreamTruncating(uri)?.use { raw ->
                    val out = if (password != null) ArchiveCrypto.encrypting(raw, password) else raw
                    JournalArchive.export(
                        entries = all,
                        resolveAudio = { File(it.audioPath).takeIf { f -> f.isFile } },
                        out = out,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                } ?: error("could not open destination")
                done(
                    getApplication<Application>().resources.getQuantityString(
                        if (password != null) R.plurals.export_done_encrypted else R.plurals.export_done,
                        all.size, all.size,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done(str(R.string.export_failed, e.message.orEmpty()))
            } finally {
                password?.fill('\u0000')
            }
        }
    }

    /**
     * Imports an archive from [uri] (from ACTION_OPEN_DOCUMENT). The store
     * runs it single-flight on the controller scope (staging, two-phase
     * insert and duplicate rules live in JournalImporter) and reports
     * through [transferStatus]; a second request while one runs gets a
     * busy message instead of a parallel import. The store also requests
     * the embedding backfill for the new rows. An encrypted archive comes
     * back as [TransferStatus.passwordFor]; call again with the password.
     */
    fun importFrom(uri: Uri, password: CharArray? = null) {
        store.import(uri, password)
    }

    override fun onCleared() {
        setExportPassword(null)
        // The recorder, transcriber, and engine belong to the controller and
        // keep running; only playback is screen-bound.
        player.stop()
    }
}
