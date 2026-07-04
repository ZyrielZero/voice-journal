package dev.zyriel.voicejournal.bench

import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import dev.zyriel.voicejournal.search.VectorSearch
import dev.zyriel.voicejournal.search.WordPieceTokenizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class BenchTest {

    @Test fun medianOddAndEven() {
        assertEquals(3L, Bench.median(listOf(5, 1, 3)))
        assertEquals(4L, Bench.median(listOf(6, 1, 2, 7)))
    }

    @Test fun measureDiscardsWarmupsAndCountsRuns() {
        var calls = 0
        val r = Bench.measure("t", warmups = 3, runs = 5) { calls++ }
        assertEquals(8, calls)
        assertEquals(5, r.runsMs.size)
        assertEquals(Bench.median(r.runsMs), r.medianMs)
    }

    @Test fun jsonContainsMetaAndResultsAndEscapes() {
        val json = Bench.toJson(
            meta = mapOf("device" to "Test \"Quoted\""),
            results = listOf(Bench.Result("a.b", listOf(1, 2, 3), 2, mapOf("k" to "v"))),
        )
        assertTrue(json.contains("\"git_sha\"").not()) // only what caller provides
        assertTrue(json.contains("\\\"Quoted\\\""))
        assertTrue(json.contains("\"median_ms\": 2"))
        assertTrue(json.contains("\"runs_ms\": [1,2,3]"))
        assertTrue(json.contains("\"k\": \"v\""))
    }

    /**
     * Container-side performance run: embedding + search suites on JVM x86.
     * These numbers validate the harness and give rough magnitudes only.
     * They are NOT device benchmarks and must not be committed as such.
     */
    @Test fun jvmPerfRun() = runBlocking {
        fun asset(n: String) = File("src/main/assets/models/$n")
        val engine = OnnxEmbeddingEngine(
            modelBytes = asset("bge_small_en_v15_q8.onnx").readBytes(),
            vocab = WordPieceTokenizer(asset("bge_vocab.txt").inputStream()),
        )
        val typical = "Today I worked on the transcription pipeline and finally got the native " +
            "build running at full speed. The optimization flag was the whole problem."
        val embed = Bench.measure("embed.typical", warmups = 2, runs = 5) {
            runBlocking { engine.embed(typical) }
        }
        engine.close()

        val rng = Random(42)
        val corpus = List(5_000) { i ->
            JournalEntry(id = i.toLong(), transcript = "e$i", timestampMs = 0, audioPath = "",
                embedding = FloatArray(384) { rng.nextFloat() - 0.5f })
        }
        val q = FloatArray(384) { rng.nextFloat() - 0.5f }
        val search = Bench.measure("search.top20.n5000", warmups = 2, runs = 5) {
            VectorSearch.topK(q, corpus, 20)
        }

        println("CONTAINER-X86 (not device): embed.typical median=${embed.medianMs}ms runs=${embed.runsMs}")
        println("CONTAINER-X86 (not device): search.n5000 median=${search.medianMs}ms runs=${search.runsMs}")
        assertTrue(embed.medianMs < 5_000)
        assertTrue(search.medianMs < 1_000)
    }
}
