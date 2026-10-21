package dev.zyriel.voicejournal.data

import android.content.Context
import android.net.Uri
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File

// STUB (wp2): minimal stand-in for WP3's JournalStore so wp2-pipeline
// compiles against contract C3 before wp3-data lands. Drop at merge.

data class ImportStatus(val message: String, val inFlight: Boolean)

class JournalStore(
    @Suppress("unused") private val app: Context,
    private val dao: JournalDao,
    private val scope: CoroutineScope,
    private val engine: () -> OnnxEmbeddingEngine?,
) {
    private val backfillMutex = Mutex()
    private val _importStatus = MutableStateFlow<ImportStatus?>(null)
    val importStatus: StateFlow<ImportStatus?> = _importStatus.asStateFlow()

    /** True while an import runs; the stub never runs one. */
    val isImporting: Boolean get() = false

    fun dismissImportStatus() { _importStatus.value = null }

    /** Deletes the WAV first, then the row, on [scope]. */
    fun delete(entry: JournalEntry) {
        scope.launch {
            File(entry.audioPath).delete()
            dao.delete(entry)
        }
    }

    /** Saves new text and a fresh vector on [scope]. */
    fun updateTranscript(entry: JournalEntry, newText: String) {
        scope.launch {
            val vec = engine()?.embed(newText)
            dao.update(entry.copy(
                transcript = newText,
                embedding = vec,
                embeddingModel = if (vec != null) OnnxEmbeddingEngine.MODEL_TAG else null,
            ))
        }
    }

    /** Single-flight backfill: one runner, compare-and-set writes. */
    fun requestBackfill() {
        scope.launch {
            if (!backfillMutex.tryLock()) return@launch
            try {
                val e = engine() ?: return@launch
                for (entry in dao.needingEmbedding(OnnxEmbeddingEngine.MODEL_TAG)) {
                    val v = e.embed(entry.transcript) ?: continue
                    dao.setEmbeddingIfUnchanged(entry.id, entry.transcript, v, OnnxEmbeddingEngine.MODEL_TAG)
                }
            } finally {
                backfillMutex.unlock()
            }
        }
    }

    /** Stub: WP3 implements the single-flight import. */
    fun import(@Suppress("UNUSED_PARAMETER") uri: Uri) {
        _importStatus.value = ImportStatus("Import is not available in the wp2 stub", inFlight = false)
    }
}
