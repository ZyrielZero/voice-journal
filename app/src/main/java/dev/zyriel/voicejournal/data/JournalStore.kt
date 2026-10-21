package dev.zyriel.voicejournal.data

import android.content.Context
import android.net.Uri
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Import progress and result, the same shape as the ViewModel's old TransferStatus. */
data class ImportStatus(val message: String, val inFlight: Boolean)

/**
 * STUB for contract C3 so the wp4-ui branch compiles before WP3 lands the
 * real class. Every body is a no-op. Drop this file at merge in favor of
 * WP3's data/JournalStore.kt.
 */
class JournalStore(
    app: Context,
    private val dao: JournalDao,
    private val scope: CoroutineScope,
    private val engine: () -> OnnxEmbeddingEngine?,
) {
    /** Deletes the WAV first, then the row, on [scope]. */
    fun delete(entry: JournalEntry) = Unit
    /** Saves new text and a fresh vector on [scope]. */
    fun updateTranscript(entry: JournalEntry, newText: String) = Unit
    /** Single-flight backfill: one runner, chunked, compare-and-set writes. */
    fun requestBackfill() = Unit
    /** Single-flight import on [scope]; progress and result go to [importStatus]. */
    fun import(uri: Uri) = Unit

    private val _importStatus = MutableStateFlow<ImportStatus?>(null)
    val importStatus: StateFlow<ImportStatus?> = _importStatus.asStateFlow()
    /** True while an import runs (WP3 addition beyond C3). */
    val isImporting: Boolean get() = false
    /** Clears a finished import's result (WP3 addition beyond C3). */
    fun dismissImportStatus() = Unit
}
