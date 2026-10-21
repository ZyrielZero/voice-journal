package dev.zyriel.voicejournal.data

import dev.zyriel.voicejournal.search.EmbeddingEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Compare-and-set backfill against an in-memory DAO. */
class EmbeddingBackfillTest {

    private val tag = "model-b"

    /** Minimal in-memory DAO; SQL semantics hand-ported for the queries backfill uses. */
    private class FakeDao(vararg rows: JournalEntry) : JournalDao {
        val rows = LinkedHashMap<Long, JournalEntry>().apply { rows.forEach { put(it.id, it) } }
        val lock = Mutex()
        var needingCalls = 0

        override suspend fun insert(entry: JournalEntry): Long = lock.withLock {
            val id = (rows.keys.maxOrNull() ?: 0) + 1
            rows[id] = entry.copy(id = id); id
        }
        override suspend fun update(entry: JournalEntry) = lock.withLock { rows[entry.id] = entry }
        override suspend fun delete(entry: JournalEntry) = lock.withLock { rows.remove(entry.id); Unit }
        override fun observeAll(): Flow<List<JournalEntry>> = flowOf(rows.values.toList())
        override suspend fun allOnce() = lock.withLock { rows.values.toList() }
        override suspend fun needingEmbedding(model: String) = lock.withLock {
            needingCalls++
            rows.values.filter { it.embedding == null || it.embeddingModel != model }
        }
        override suspend fun setEmbeddingIfUnchanged(id: Long, transcript: String, embedding: FloatArray, model: String) =
            lock.withLock {
                val r = rows[id]
                if (r == null || r.transcript != transcript) 0
                else { rows[id] = r.copy(embedding = embedding, embeddingModel = model); 1 }
            }
        override suspend fun setTranscript(id: Long, transcript: String) = lock.withLock {
            val r = rows[id] ?: return@withLock 0
            rows[id] = r.copy(transcript = transcript, embedding = null, embeddingModel = null); 1
        }
        override suspend fun countByAudioPath(path: String) = lock.withLock { rows.values.count { it.audioPath == path } }
    }

    private fun row(id: Long, text: String, model: String? = null, v: FloatArray? = null) =
        JournalEntry(id = id, transcript = text, timestampMs = id, audioPath = "", embedding = v, embeddingModel = model)

    private fun vecOf(text: String) = floatArrayOf(text.length.toFloat())

    private val transactions = mutableListOf<Int>()

    private fun backfill(dao: FakeDao, engine: EmbeddingEngine?, chunk: Int = 32) = EmbeddingBackfill(
        dao = dao,
        inTransaction = { block -> transactions += 1; block() },
        engine = { engine },
        modelTag = tag,
        chunkSize = chunk,
    )

    private val plainEngine = object : EmbeddingEngine {
        override suspend fun embed(text: String) = vecOf(text)
    }

    @Test
    fun editDuringEmbedIsNotRevertedAndItsVectorIsNotOverwritten() = runBlocking {
        val dao = FakeDao(row(1, "old text"))
        // The user edits the row while backfill is embedding the old text.
        val engine = object : EmbeddingEngine {
            override suspend fun embed(text: String): FloatArray {
                if (text == "old text") dao.setTranscript(1, "edited")
                return vecOf(text)
            }
        }
        val r = backfill(dao, engine).runOnce()
        assertEquals(EmbeddingBackfill.Report(written = 0, skippedEdited = 1, failed = 0), r)
        val now = dao.rows.getValue(1)
        assertEquals("edit survives", "edited", now.transcript)
        assertNull("stale vector dropped", now.embedding)

        // The next pass embeds the edited text.
        assertEquals(1, backfill(dao, plainEngine).runOnce().written)
        assertArrayEquals(vecOf("edited"), dao.rows.getValue(1).embedding, 0f)
    }

    @Test
    fun migratedRowsWithNullModelAndOtherModelRowsAreEmbedded() = runBlocking {
        val dao = FakeDao(
            row(1, "v1 row", model = null, v = floatArrayOf(1f)), // pre-migration: vector, NULL model
            row(2, "other model", model = "model-a", v = floatArrayOf(1f)),
            row(3, "current", model = tag, v = floatArrayOf(9f)),
            row(4, "never embedded"),
        )
        val r = backfill(dao, plainEngine).runOnce()
        assertEquals(3, r.written)
        assertArrayEquals(floatArrayOf(9f), dao.rows.getValue(3).embedding, 0f)
        assertEquals(setOf(tag), dao.rows.values.map { it.embeddingModel }.toSet())
    }

    @Test
    fun chunksAreWrittenOneTransactionEach() = runBlocking {
        val dao = FakeDao(*(1L..5L).map { row(it, "t$it") }.toTypedArray())
        backfill(dao, plainEngine, chunk = 2).runOnce()
        assertEquals("5 rows in chunks of 2", 3, transactions.size)
        assertEquals(5, dao.rows.values.count { it.embeddingModel == tag })
    }

    @Test
    fun embedFailuresAreCountedNotThrown() = runBlocking {
        val dao = FakeDao(row(1, "ok"), row(2, "bad"), row(3, "throws"))
        val engine = object : EmbeddingEngine {
            override suspend fun embed(text: String): FloatArray? = when (text) {
                "bad" -> null
                "throws" -> error("ort hiccup")
                else -> vecOf(text)
            }
        }
        assertEquals(EmbeddingBackfill.Report(1, 0, 2), backfill(dao, engine).runOnce())
    }

    @Test
    fun noEngineIsANoOp() = runBlocking {
        val dao = FakeDao(row(1, "x"))
        assertEquals(EmbeddingBackfill.Report(0, 0, 0), backfill(dao, null).runOnce())
        assertEquals(0, dao.needingCalls)
    }

    @Test
    fun concurrentRequestsShareOneRunnerAndLateRowsStillGetEmbedded() = runBlocking {
        val dao = FakeDao(row(1, "first"))
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val engine = object : EmbeddingEngine {
            override suspend fun embed(text: String): FloatArray {
                active++; maxActive = maxOf(maxActive, active)
                if (text == "first") gate.await()
                active--
                return vecOf(text)
            }
        }
        val bf = backfill(dao, engine)
        withTimeout(5_000) {
            val runner = async(Dispatchers.Default) { bf.request() }
            while (dao.needingCalls == 0) yield()
            // A row lands mid-pass (an import), and a second request comes in.
            dao.insert(row(0, "late"))
            bf.request() // returns at once: the runner holds the mutex
            gate.complete(Unit)
            runner.await()
        }
        assertEquals("one runner at a time", 1, maxActive)
        assertEquals("runner did the extra pass", 2, dao.needingCalls)
        assertEquals(2, dao.rows.values.count { it.embeddingModel == tag })
    }
}
