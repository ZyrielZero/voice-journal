package dev.zyriel.voicejournal.search

import dev.zyriel.voicejournal.TestPaths
import dev.zyriel.voicejournal.data.JournalEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where [HybridSearch.SEMANTIC_FLOOR] and [HybridSearch.SEMANTIC_MARGIN]
 * come from. Runs the bundled int8 model over a small journal and a set of
 * short queries, each labelled with the entries a person would expect
 * back, and checks what the search is supposed to guarantee:
 *
 *  - every expected entry is returned, and the top result is one of them;
 *  - the unexpected results behind them are few;
 *  - a query about something the journal never mentions returns nothing,
 *    or at most one loosely adjacent entry.
 *
 * It does not claim a clean separation, because there is none: bge
 * similarities sit in a narrow band, and the printed table shows unrelated
 * pairs scoring as high as 0.59 while a real match can be as low as 0.54.
 * The floor and margin are therefore a trim, not a relevance oracle, and
 * twelve entries are a small sample. If the model, its quantization or the
 * query prefix changes, this test is what has to pass again, and the
 * printed table (lines starting CAL) is what to pick new values from.
 */
class SearchCalibrationTest {

    private fun asset(name: String) = TestPaths.mainAsset("models/$name")

    private val journal = listOf(
        "Had a long talk with my advisor about the thesis today. She wants the introduction rewritten before the committee meets.",
        "Felt anxious all afternoon about the dissertation deadline. I keep putting off the methods chapter.",
        "Went for a run by the river this morning, about five kilometres. Legs felt heavy but the weather was perfect.",
        "Made pasta with roasted tomatoes for dinner and tried the new bread recipe. The crust came out too dark.",
        "Called mom. She told me about the garden and that dad has been fixing the fence all week.",
        "Work was frustrating. The deploy failed twice and I spent hours chasing a bug in the payment service.",
        "Dentist appointment on Tuesday. I need to remember to ask about the wisdom tooth.",
        "Couldn't sleep last night, kept thinking about whether to take the job offer in Denver.",
        "Booked flights to Lisbon for our anniversary in May. Need to find a place to stay near the old town.",
        "The car made a strange grinding noise again. Taking it to the mechanic on Friday.",
        "Read a few chapters of the novel before bed. The main character finally left the island.",
        "Budget check: spent too much on eating out this month, need to cut back on takeaway.",
    )

    /** Query -> indices into [journal] that must come back. */
    private val expected = linkedMapOf(
        "thesis" to setOf(0, 1),
        "stress about my dissertation" to setOf(0, 1),
        "exercise" to setOf(2),
        "what did I cook" to setOf(3),
        "my parents" to setOf(4),
        "problems at work" to setOf(5),
        "trouble sleeping" to setOf(7),
        "travel plans" to setOf(8),
        "car repair" to setOf(9),
        "money" to setOf(11),
    )

    /** Things this journal never mentions. */
    private val absent = listOf(
        "quantum physics lecture",
        "skiing in the Alps",
        "my cat's birthday",
        "presidential election",
        "learning to play the violin",
    )

    @Test
    fun floorSeparatesWhatBelongsFromWhatDoesNot() = runBlocking {
        val engine = OnnxEmbeddingEngine(
            modelBytes = asset("bge_small_en_v15_q8.onnx").readBytes(),
            vocab = WordPieceTokenizer(asset("bge_vocab.txt").inputStream()),
        )
        val entries = journal.mapIndexed { i, text ->
            JournalEntry(id = i.toLong(), transcript = text, timestampMs = i.toLong(), audioPath = "", embedding = engine.embed(text)!!)
        }
        val floor = HybridSearch.SEMANTIC_FLOOR
        var lowestExpected = 1f
        var highestOther = 0f
        var highestAbsent = 0f
        var extras = 0

        for ((query, want) in expected) {
            val qv = engine.embedQuery(query)!!
            val sims = entries.map { VectorSearch.bestCosine(qv, it.embedding!!)!! }
            lowestExpected = minOf(lowestExpected, want.minOf { sims[it] })
            highestOther = maxOf(highestOther, sims.filterIndexed { i, _ -> i !in want }.max())
            println("CAL %-30s want=%s  %s".format(query, want.map { "%.3f".format(sims[it]) },
                sims.mapIndexed { i, s -> if (i in want) "" else "%.2f".format(s) }.filter { it.isNotEmpty() }))

            // Semantics only (a made-up query string defeats the keyword side).
            val hits = HybridSearch.rank("zzqx", qv, entries, 20).map { it.entry.id.toInt() }
            assertTrue("'$query' should return $want, got $hits", hits.containsAll(want))
            assertTrue("'$query' top hit ${hits.first()} not in $want", hits.first() in want)
            extras += hits.size - want.size
            assertTrue("'$query' returned too many unrelated entries: $hits", hits.size - want.size <= 5)
        }
        // Behind the expected entries, about two strays per query on average.
        assertTrue("too many unrelated results overall: $extras", extras <= 2 * expected.size)
        for (query in absent) {
            val qv = engine.embedQuery(query)!!
            val sims = entries.map { VectorSearch.bestCosine(qv, it.embedding!!)!! }
            highestAbsent = maxOf(highestAbsent, sims.max())
            println("CAL absent %-30s max=%.3f".format(query, sims.max()))
        }
        println("CAL floor=$floor lowestExpected=$lowestExpected highestUnexpected=$highestOther highestAbsent=$highestAbsent")
        var empty = 0
        for (query in absent) {
            val qv = engine.embedQuery(query)!!
            val hits = HybridSearch.rank(query, qv, entries, 20).map { it.entry.id }
            assertTrue("'$query' is not in the journal, got $hits", hits.size <= 1)
            if (hits.isEmpty()) empty++
        }
        assertTrue("most absent topics must return nothing, only $empty of ${absent.size} did", empty >= absent.size - 1)
        engine.close()
    }
}
