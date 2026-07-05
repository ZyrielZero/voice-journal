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
import dev.zyriel.voicejournal.pipeline.RecordingController
import dev.zyriel.voicejournal.search.KeywordSearch
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import dev.zyriel.voicejournal.search.VectorSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * UI-facing state for the journal screen. Deliberately owns nothing that must
 * outlive the Activity: the record/transcribe/insert pipeline lives in
 * [RecordingController], which survives configuration changes, backgrounding,
 * and Activity death. This ViewModel handles what only matters while the
 * screen exists: search, playback, and transcript edits.
 */
class JournalViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = RecordingController.get(app)
    private val dao = JournalDb.get(app).dao()

    val pipeline: StateFlow<RecordingController.PipelineState> = controller.pipeline
    val recorder = controller.recorder
    val player = AudioPlayer()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Entries, filtered by semantic search when a query is active. */
    val entries: StateFlow<List<JournalEntry>> =
        combine(dao.observeAll(), _query) { all, q -> all to q }
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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(q: String) { _query.value = q }

    fun toggleRecording() {
        player.stop()
        controller.toggleRecording()
    }

    fun play(entry: JournalEntry) {
        val f = File(entry.audioPath)
        if (player.playingFile.value == f) player.stop() else if (f.exists()) player.play(f)
    }

    fun updateTranscript(entry: JournalEntry, newText: String) {
        viewModelScope.launch {
            val vec = controller.engine?.embed(newText)
            dao.update(entry.copy(
                transcript = newText,
                embedding = vec,
                embeddingModel = if (vec != null) OnnxEmbeddingEngine.MODEL_TAG else null,
            ))
        }
    }

    fun delete(entry: JournalEntry) {
        viewModelScope.launch {
            player.stop()
            dao.delete(entry)
            File(entry.audioPath).delete()
        }
    }

    fun dismissError() { controller.dismissError() }

    // ---- Export / import ----

    /** Human-readable transfer status for the UI; null when nothing to show. */
    private val _transferStatus = MutableStateFlow<String?>(null)
    val transferStatus: StateFlow<String?> = _transferStatus.asStateFlow()

    fun dismissTransferStatus() { _transferStatus.value = null }

    /**
     * Writes the whole journal to [uri] (from ACTION_CREATE_DOCUMENT).
     * Runs on viewModelScope: transfers are user-initiated with the screen
     * open, and unlike recording there is no OS resource to keep legal.
     * Known limitation: backgrounding mid-export abandons a partial zip at
     * the destination; SAF gives no atomic write to fix that from here.
     */
    fun exportTo(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _transferStatus.value = "Exporting..."
            runCatching {
                val all = dao.allOnce().sortedByDescending { it.timestampMs }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    JournalArchive.export(
                        entries = all,
                        resolveAudio = { File(it.audioPath).takeIf { f -> f.isFile } },
                        out = out,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                } ?: error("could not open destination")
                all.size
            }.onSuccess { n ->
                _transferStatus.value = "Exported $n ${if (n == 1) "entry" else "entries"}."
            }.onFailure { e ->
                _transferStatus.value = "Export failed: ${e.message}"
            }
        }
    }

    /**
     * Imports an archive from [uri] (from ACTION_OPEN_DOCUMENT). Audio
     * lands in the recordings dir under a fresh name so nothing existing
     * is overwritten; entries matching an existing (timestamp, transcript)
     * pair are skipped so re-importing your own backup is a no-op. New
     * entries insert with null embeddings and the controller's backfill
     * vectorizes them, same as first-launch recovery.
     */
    fun importFrom(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _transferStatus.value = "Importing..."
            runCatching {
                val app = getApplication<Application>()
                val dir = File(app.filesDir, "recordings").apply { mkdirs() }
                val imported = app.contentResolver.openInputStream(uri)?.use { inp ->
                    JournalArchive.import(inp) { suggested, data ->
                        var f = File(dir, "imported_$suggested")
                        var i = 1
                        while (f.exists()) f = File(dir, "imported_${i++}_$suggested")
                        f.outputStream().use { data.copyTo(it) }
                        f.absolutePath
                    }
                } ?: error("could not open archive")

                val existing = dao.allOnce().map { it.timestampMs to it.transcript }.toHashSet()
                var added = 0
                for (e in imported) {
                    if (e.timestampMs to e.transcript in existing) {
                        // Duplicate of something already here; drop any audio
                        // the sink wrote for it so imports stay re-runnable.
                        e.audioPath?.let { File(it).delete() }
                        continue
                    }
                    dao.insert(JournalEntry(
                        transcript = e.transcript,
                        timestampMs = e.timestampMs,
                        audioPath = e.audioPath ?: "",
                    ))
                    added++
                }
                controller.requestBackfill()
                added to imported.size
            }.onSuccess { (added, total) ->
                _transferStatus.value = when {
                    total == 0 -> "Archive was empty."
                    added == 0 -> "Nothing new: all $total entries already here."
                    else -> "Imported $added of $total entries."
                }
            }.onFailure { e ->
                _transferStatus.value = "Import failed: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        // The recorder, transcriber, and engine belong to the controller and
        // keep running; only playback is screen-bound.
        player.stop()
    }
}
