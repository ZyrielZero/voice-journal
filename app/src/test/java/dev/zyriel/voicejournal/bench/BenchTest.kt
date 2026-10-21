package dev.zyriel.voicejournal.bench

import dev.zyriel.voicejournal.TestPaths
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import dev.zyriel.voicejournal.search.VectorSearch
import dev.zyriel.voicejournal.search.WordPieceTokenizer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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

    @Test fun jsonEscapesControlCharactersAndRoundTrips() {
        val nasty = "line one\nline \"two\"\ttab\r\\back\u0001\u001f\b\u000c end \u2019"
        val json = Bench.toJson(
            meta = mapOf("note" to nasty, "k\n" to "v"),
            results = listOf(Bench.Result("r", listOf(1), 1, mapOf("hypothesis" to nasty))),
        )
        // No raw control character may survive inside the output's strings.
        val body = json.replace("\n", "")
        assertTrue("raw control char in $json", body.none { it < ' ' })
        // And a real parser reads back exactly what went in.
        val root = JSONObject(json)
        assertEquals(nasty, root.getJSONObject("meta").getString("note"))
        assertEquals("v", root.getJSONObject("meta").getString("k\n"))
        val extra = root.getJSONArray("results").getJSONObject(0).getJSONObject("extra")
        assertEquals(nasty, extra.getString("hypothesis"))
    }

    @Test fun jsonEscapeUsesShortFormsAndUnicodeEscapes() {
        assertEquals("\\n\\r\\t\\b\\f", Bench.jsonEscape("\n\r\t\b\u000c"))
        assertEquals("\\u0001\\u001f", Bench.jsonEscape("\u0001\u001f"))
        assertEquals("\\\"\\\\", Bench.jsonEscape("\"\\"))
        assertEquals("caf\u00e9 \u2019", Bench.jsonEscape("caf\u00e9 \u2019"))
    }

    /**
     * Container-side performance run: embedding + search suites on JVM x86.
     * These numbers validate the harness and give rough magnitudes only.
     * They are NOT device benchmarks and must not be committed as such.
     */
    @Test fun jvmPerfRun() = runBlocking {
        fun asset(n: String) = TestPaths.mainAsset("models/$n")
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
        // The harness itself is always checked; the wall-clock bounds are not,
        // because shared CI runners are too noisy for latency assertions.
        // Opt in with ./gradlew testDebugUnitTest -Pvj.perfAssertions=true.
        assertEquals(5, embed.runsMs.size)
        assertEquals(5, search.runsMs.size)
        assertTrue((embed.runsMs + search.runsMs).all { it >= 0 })
        if (TestPaths.perfAssertions) {
            assertTrue(embed.medianMs < 5_000)
            assertTrue(search.medianMs < 1_000)
        }
    }
}
