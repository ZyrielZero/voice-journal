package dev.zyriel.voicejournal.data

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.room.withTransaction
import dev.zyriel.voicejournal.search.EmbeddingEngine
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Import progress and result for the UI. [inFlight] gates dismissal.
 * [passwordFor] is set when the archive at that Uri is encrypted and needs
 * a password (first ask, or after a wrong one): the UI collects it and
 * calls [JournalStore.import] again with it.
 */
data class ImportStatus(
    val message: String,
    val inFlight: Boolean,
    val passwordFor: Uri? = null,
    /** True when [passwordFor] follows a password that did not open the file. */
    val wrongPassword: Boolean = false,
)

/**
 * Process-lived owner of every journal write that must survive the UI:
 * deletes, transcript edits, embedding backfill and imports. The
 * RecordingController constructs one on its own scope, so none of these
 * dies with a ViewModel or races a second copy of itself.
 *
 * Ordering rules, each closing a review finding:
 *  - [delete] removes the WAV before the row. Interrupted, it leaves a row
 *    with no audio (a silent Play the user can delete again), never an
 *    unreferenced WAV that the orphan sweep would resurrect as an entry.
 *  - [updateTranscript] commits the text (clearing the stale vector) in
 *    one statement, then embeds and writes the vector compare-and-set, so
 *    a slow embed can never overwrite a newer edit.
 *  - Backfill is single-flight and compare-and-set ([EmbeddingBackfill]).
 *  - [import] is single-flight process-wide, stages under a per-run
 *    directory, and follows the `.importing` sidecar protocol
 *    ([JournalImporter]).
 */
class JournalStore(
    app: Context,
    private val dao: JournalDao,
    private val scope: CoroutineScope,
    private val engine: () -> EmbeddingEngine?,
) {
    private val context = app.applicationContext
    private val db = JournalDb.get(context)
    private val recordingsDir = File(context.filesDir, "recordings")
    private val stagingRoot = File(context.cacheDir, "import-staging")

    private val backfill = EmbeddingBackfill(
        dao = dao,
        inTransaction = { block -> db.withTransaction { block() } },
        engine = engine,
        modelTag = OnnxEmbeddingEngine.MODEL_TAG,
        log = { Log.i(TAG, it) },
    )

    private val _importStatus = MutableStateFlow<ImportStatus?>(null)
    val importStatus: StateFlow<ImportStatus?> = _importStatus.asStateFlow()

    /**
     * True while an import runs anywhere in the process. The orphan sweep
     * can consult it: a WAV with a `.importing` sidecar is live, not
     * stranded, while this is true.
     */
    val isImporting: Boolean get() = importRunning.get()

    /** Deletes the WAV first, then the row, on [scope]. */
    fun delete(entry: JournalEntry) {
        scope.launch(Dispatchers.IO) {
            try {
                if (entry.audioPath.isNotEmpty()) File(entry.audioPath).delete()
                dao.delete(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "delete of entry ${entry.id} failed: ${e.message}")
            }
        }
    }

    /** Saves new text and a fresh vector on [scope]. */
    fun updateTranscript(entry: JournalEntry, newText: String) {
        scope.launch {
            try {
                if (dao.setTranscript(entry.id, newText) == 0) return@launch // deleted meanwhile
                val v = engine()?.embed(newText) ?: return@launch // backfill retries the null vector
                dao.setEmbeddingIfUnchanged(entry.id, newText, v, OnnxEmbeddingEngine.MODEL_TAG)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "transcript update of entry ${entry.id} failed: ${e.message}")
            }
        }
    }

    /** Single-flight backfill: one runner, chunked, compare-and-set writes. */
    fun requestBackfill() {
        scope.launch {
            try {
                backfill.request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "backfill failed: ${e.message}")
            }
        }
    }

    /**
     * Single-flight import on [scope]; a second call while one runs leaves
     * the running import alone and reports busy. Progress and result are
     * reported via [importStatus]. An encrypted archive with no (or the
     * wrong) [password] ends with [ImportStatus.passwordFor] set instead
     * of an error; nothing has been imported at that point, because the
     * archive's first frame is authenticated before any of it is read.
     * The array is wiped when the import ends.
     */
    fun import(uri: Uri, password: CharArray? = null) {
        if (!importRunning.compareAndSet(false, true)) {
            _importStatus.value = ImportStatus(BUSY_MESSAGE, inFlight = true)
            return
        }
        _importStatus.value = ImportStatus("Importing...", inFlight = true)
        val job = scope.launch(Dispatchers.IO) {
            try {
                val result = runImport(uri, password)
                if (result.added > 0) requestBackfill()
                finish(when {
                    result.total == 0 -> "Archive was empty."
                    result.added == 0 -> "Nothing new: all ${result.total} entries already here."
                    else -> "Imported ${result.added} of ${result.total} entries."
                })
            } catch (e: CancellationException) {
                finish("Import cancelled.")
                throw e
            } catch (e: ArchiveCrypto.PasswordRequiredException) {
                _importStatus.value = ImportStatus(e.message.orEmpty(), inFlight = false, passwordFor = uri)
            } catch (e: ArchiveCrypto.WrongPasswordException) {
                _importStatus.value = ImportStatus(
                    e.message.orEmpty(), inFlight = false, passwordFor = uri, wrongPassword = true,
                )
            } catch (e: Exception) {
                finish("Import failed: ${e.message}")
            } finally {
                password?.fill('\u0000')
            }
        }
        // invokeOnCompletion, not finally: it also runs when the scope was
        // already cancelled and the body never started.
        job.invokeOnCompletion { importRunning.set(false) }
    }

    /** Clears a finished import's result. An import in flight keeps its status. */
    fun dismissImportStatus() {
        _importStatus.value = _importStatus.value?.takeIf { it.inFlight && importRunning.get() }
    }

    private fun finish(message: String) {
        _importStatus.value = ImportStatus(message, inFlight = false)
    }

    private suspend fun runImport(uri: Uri, password: CharArray?): JournalImporter.Result {
        // Only one import runs per process (importRunning), so every other
        // run directory is a leftover from a process that died mid-import.
        val runDir = File(stagingRoot, "run-${System.currentTimeMillis()}")
        stagingRoot.listFiles()?.forEach { if (it != runDir) it.deleteRecursively() }
        val importer = JournalImporter(
            stagingDir = runDir,
            recordingsDir = recordingsDir,
            freeBytes = { StatFs(runDir.path).availableBytes },
        )
        val existing = dao.allOnce().map { it.timestampMs to it.transcript }.toHashSet()
        return context.contentResolver.openInputStream(uri)?.use { raw ->
            val buffered = BufferedInputStream(raw)
            val inp = when {
                !ArchiveCrypto.isEncrypted(buffered) -> buffered
                password == null || password.isEmpty() -> throw ArchiveCrypto.PasswordRequiredException()
                else -> ArchiveCrypto.decrypting(buffered, password)
            }
            importer.import(inp, existing) { e ->
                dao.insert(JournalEntry(
                    transcript = e.transcript,
                    timestampMs = e.timestampMs,
                    audioPath = e.audioPath ?: "",
                ))
            }
        } ?: error("could not open archive")
    }

    companion object {
        private const val TAG = "JournalStore"
        const val BUSY_MESSAGE = "An import is already running. It will finish on its own."

        /** Process-wide, so even a second store cannot run a parallel import. */
        private val importRunning = AtomicBoolean(false)
    }
}
