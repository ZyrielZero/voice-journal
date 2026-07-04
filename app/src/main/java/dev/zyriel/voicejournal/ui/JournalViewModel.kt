package dev.zyriel.voicejournal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zyriel.voicejournal.audio.AudioPlayer
import dev.zyriel.voicejournal.data.JournalDb
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.pipeline.RecordingController
import dev.zyriel.voicejournal.search.KeywordSearch
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import dev.zyriel.voicejournal.search.VectorSearch
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

    override fun onCleared() {
        // The recorder, transcriber, and engine belong to the controller and
        // keep running; only playback is screen-bound.
        player.stop()
    }
}
