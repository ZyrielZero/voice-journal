package dev.zyriel.voicejournal.data

import dev.zyriel.voicejournal.search.EmbeddingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vectorizes every entry that has no vector, or one from another model.
 *
 * Two rules keep it from fighting the user:
 *  - One runner at a time. Requests that arrive while a pass is running
 *    mark the work dirty and return; the running pass loops once more, so
 *    rows inserted mid-pass (an import) are still picked up.
 *  - Compare-and-set writes. Each chunk is embedded outside any
 *    transaction (embedding takes tens of milliseconds per entry and must
 *    not hold the write lock), then written in one transaction through
 *    [JournalDao.setEmbeddingIfUnchanged], which only lands if the
 *    transcript is still the one that was embedded. A row edited in the
 *    meantime returns 0 and is skipped; its edit already saved its own
 *    vector, or cleared it for the next pass. The old code wrote a whole
 *    stale snapshot with @Update and could revert the edit.
 *
 * A failed embed (null, per the engine contract) or a failed chunk write
 * is counted and logged, never thrown: this runs unattended at every
 * launch and after imports. The next request retries those rows.
 *
 * Pure JVM apart from the DAO interface, so it is unit-tested with a fake.
 */
class EmbeddingBackfill(
    private val dao: JournalDao,
    /** Runs the block in one database transaction; Room's withTransaction on device. */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val engine: () -> EmbeddingEngine?,
    private val modelTag: String,
    private val chunkSize: Int = 32,
    private val log: (String) -> Unit = {},
) {
    data class Report(val written: Int, val skippedEdited: Int, val failed: Int)

    private val mutex = Mutex()
    private val dirty = AtomicBoolean(false)

    /**
     * Requests a pass. Returns at once when another caller is already
     * running one (that runner will do the extra pass); otherwise drains
     * all pending work before returning.
     */
    suspend fun request() {
        dirty.set(true)
        // The outer loop closes the race where a request lands after the
        // runner's last dirty check but before it unlocks: that request's
        // tryLock fails, so the runner must look at the flag again.
        while (dirty.get()) {
            if (!mutex.tryLock()) return
            try {
                while (dirty.getAndSet(false)) runOnce()
            } finally {
                mutex.unlock()
            }
        }
    }

    /** One pass over the rows needing a vector. Callers serialize through [request]. */
    internal suspend fun runOnce(): Report {
        val e = engine() ?: return Report(0, 0, 0)
        var written = 0
        var skipped = 0
        var failed = 0
        for (chunk in dao.needingEmbedding(modelTag).chunked(chunkSize)) {
            val vectors = ArrayList<Triple<Long, String, FloatArray>>(chunk.size)
            for (entry in chunk) {
                val v = try {
                    e.embed(entry.transcript)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    null
                }
                if (v == null) failed++ else vectors += Triple(entry.id, entry.transcript, v)
            }
            if (vectors.isEmpty()) continue
            try {
                var w = 0
                inTransaction {
                    w = 0
                    for ((id, transcript, v) in vectors) {
                        w += dao.setEmbeddingIfUnchanged(id, transcript, v, modelTag)
                    }
                }
                written += w
                skipped += vectors.size - w
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                failed += vectors.size
                log("backfill chunk of ${vectors.size} failed: ${ex.message}")
            }
        }
        return Report(written, skipped, failed).also {
            if (it.written + it.skippedEdited + it.failed > 0) {
                log("backfill: ${it.written} written, ${it.skippedEdited} skipped (edited), ${it.failed} failed")
            }
        }
    }
}
