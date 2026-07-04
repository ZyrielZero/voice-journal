package dev.zyriel.voicejournal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zyriel.voicejournal.audio.AudioPlayer
import dev.zyriel.voicejournal.audio.AudioRecorder
import dev.zyriel.voicejournal.data.JournalDb
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.search.KeywordSearch
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import dev.zyriel.voicejournal.search.VectorSearch
import dev.zyriel.voicejournal.search.WordPieceTokenizer
import dev.zyriel.voicejournal.whisper.Transcriber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JournalViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface PipelineState {
        data object Idle : PipelineState
        data object Recording : PipelineState
        data object Transcribing : PipelineState
        data class Failed(val message: String) : PipelineState
    }

    val recorder = AudioRecorder(viewModelScope)
    val player = AudioPlayer()
    private val dao = JournalDb.get(app).dao()
    private val transcriber = Transcriber(app)
    private val dir = File(app.filesDir, "recordings").apply { mkdirs() }

    /** Null until initialized; stays null if init fails, and search falls back to keywords. */
    @Volatile private var engine: OnnxEmbeddingEngine? = null

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            engine = runCatching {
                OnnxEmbeddingEngine(
                    modelBytes = app.assets.open("models/bge_small_en_v15_q8.onnx").use { it.readBytes() },
                    vocab = app.assets.open("models/bge_vocab.txt").use { WordPieceTokenizer(it) },
                )
            }.getOrNull()
            backfillEmbeddings()
        }
    }

    /** Embeds entries that have no vector or a vector from a different model. */
    private suspend fun backfillEmbeddings() {
        val e = engine ?: return
        for (entry in dao.needingEmbedding(OnnxEmbeddingEngine.MODEL_TAG)) {
            val v = e.embed(entry.transcript) ?: continue
            dao.update(entry.copy(embedding = v, embeddingModel = OnnxEmbeddingEngine.MODEL_TAG))
        }
    }

    private val _pipeline = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipeline: StateFlow<PipelineState> = _pipeline.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Entries, filtered by keyword search when a query is active. */
    val entries: StateFlow<List<JournalEntry>> =
        combine(dao.observeAll(), _query) { all, q -> all to q }
            .mapLatest { (all, q) ->
                when {
                    q.isBlank() -> all
                    else -> {
                        val e = engine
                        val qVec = e?.embedQuery(q)
                        if (qVec != null) VectorSearch.topK(qVec, all, k = 20).map { it.first }
                        else KeywordSearch.rank(q, all, k = 20).map { it.first }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var activeFile: File? = null

    fun setQuery(q: String) { _query.value = q }

    fun toggleRecording() {
        when {
            recorder.state.value is AudioRecorder.State.Recording -> {
                _pipeline.value = PipelineState.Transcribing
                val file = activeFile ?: return
                viewModelScope.launch {
                    try {
                        recorder.stopAndWait()   // WAV header must be finalized first
                        val text = transcriber.transcribe(file)
                        val finalText = text.ifBlank { "(no speech detected)" }
                        val vec = engine?.embed(finalText)
                        dao.insert(JournalEntry(
                            transcript = finalText,
                            timestampMs = System.currentTimeMillis(),
                            audioPath = file.absolutePath,
                            embedding = vec,
                            embeddingModel = if (vec != null) OnnxEmbeddingEngine.MODEL_TAG else null,
                        ))
                        _pipeline.value = PipelineState.Idle
                    } catch (e: Exception) {
                        _pipeline.value = PipelineState.Failed(e.message ?: "transcription failed")
                    }
                }
            }
            _pipeline.value is PipelineState.Transcribing -> Unit // wait
            else -> {
                player.stop()
                val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val f = File(dir, "entry_$name.wav")
                activeFile = f
                _pipeline.value = PipelineState.Recording
                recorder.start(f)
            }
        }
    }

    fun play(entry: JournalEntry) {
        val f = File(entry.audioPath)
        if (player.playingFile.value == f) player.stop() else if (f.exists()) player.play(f)
    }

    fun updateTranscript(entry: JournalEntry, newText: String) {
        viewModelScope.launch {
            val vec = engine?.embed(newText)
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

    fun dismissError() { _pipeline.value = PipelineState.Idle }

    override fun onCleared() {
        recorder.stop()
        player.stop()
        transcriber.release()
        engine?.close()
    }
}
