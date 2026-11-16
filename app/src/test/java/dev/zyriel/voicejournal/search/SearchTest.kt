package dev.zyriel.voicejournal.search

import dev.zyriel.voicejournal.data.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private fun entry(id: Long, text: String, emb: FloatArray? = null) =
        JournalEntry(id = id, transcript = text, timestampMs = id, audioPath = "/x/$id.wav", embedding = emb)

    // VectorSearch

    @Test fun cosineOfIdenticalVectorsIsOne() {
        val v = floatArrayOf(0.3f, -0.7f, 0.648f)
        assertEquals(1f, VectorSearch.cosine(v, v), 1e-5f)
    }

    @Test fun cosineOfOrthogonalVectorsIsZero() {
        assertEquals(0f, VectorSearch.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test fun cosineOfOppositeVectorsIsMinusOne() {
        assertEquals(-1f, VectorSearch.cosine(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f)), 1e-5f)
    }

    @Test fun cosineDimensionMismatchThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            VectorSearch.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f))
        }
    }

    @Test fun zeroVectorScoresZeroInsteadOfNaN() {
        assertEquals(0f, VectorSearch.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
    }

    @Test fun topKRanksBySimilarityAndSkipsUnembedded() {
        val q = floatArrayOf(1f, 0f)
        val entries = listOf(
            entry(1, "far", floatArrayOf(0f, 1f)),
            entry(2, "close", floatArrayOf(0.9f, 0.1f)),
            entry(3, "no vector", null),
            entry(4, "exact", floatArrayOf(1f, 0f)),
        )
        val top = VectorSearch.topK(q, entries, k = 2)
        assertEquals(listOf(4L, 2L), top.map { it.first.id })
    }

    // KeywordSearch

    @Test fun rankFindsTokenMatchesCaseInsensitive() {
        val entries = listOf(
            entry(1, "Worked on my thesis introduction today"),
            entry(2, "Went grocery shopping"),
        )
        val r = KeywordSearch.rank("THESIS", entries, 5)
        assertEquals(1, r.size)
        assertEquals(1L, r[0].first.id)
    }

    @Test fun fullerTokenCoverageRanksHigher() {
        // Entry 2 has both query words but out of order, so the exact-phrase
        // bonus cannot fire: it must win on token coverage alone.
        val entries = listOf(
            entry(1, "thesis draft going slowly"),
            entry(2, "defense prep for the thesis starts now"),
        )
        val r = KeywordSearch.rank("thesis defense", entries, 5)
        assertEquals(2L, r[0].first.id)
        assertEquals(1f, r[0].second, 1e-6f)    // full coverage, no phrase bonus
        assertEquals(0.5f, r[1].second, 1e-6f)  // half coverage
    }

    @Test fun exactPhraseGetsBonus() {
        val entries = listOf(
            entry(1, "defense of the thesis went fine"),
            entry(2, "thesis defense went fine"),
        )
        val r = KeywordSearch.rank("thesis defense", entries, 5)
        assertEquals(2L, r[0].first.id)
    }

    @Test fun noMatchesReturnsEmpty() {
        assertTrue(KeywordSearch.rank("quantum", listOf(entry(1, "made pasta")), 5).isEmpty())
    }

    @Test fun blankQueryReturnsEmpty() {
        assertTrue(KeywordSearch.rank("  ", listOf(entry(1, "anything")), 5).isEmpty())
    }

    @Test fun spokenQuestionWordsDoNotMatchEverything() {
        // "my" is in both entries; only "thesis" says what the query is about.
        val entries = listOf(
            entry(1, "walked my dog around the lake"),
            entry(2, "finished a chapter of my thesis"),
        )
        val r = KeywordSearch.rank("what did I say about my thesis", entries, 5)
        assertEquals(listOf(2L), r.map { it.first.id })
        assertEquals(1f, r[0].second, 1e-6f)
    }

    @Test fun aQueryOfOnlyCommonWordsStillSearches() {
        val r = KeywordSearch.rank("what about it", listOf(entry(1, "thinking about it more"), entry(2, "pasta")), 5)
        assertEquals(listOf(1L), r.map { it.first.id })
    }

    // Multi-window vectors

    @Test fun bestCosineScoresALongEntryByItsBestWindow() {
        val q = floatArrayOf(1f, 0f)
        // Three windows back to back; the middle one matches the query.
        val stored = floatArrayOf(0f, 1f, 1f, 0f, -1f, 0f)
        assertEquals(1f, VectorSearch.bestCosine(q, stored)!!, 1e-6f)
        assertEquals(VectorSearch.cosine(q, floatArrayOf(0.6f, 0.8f)), VectorSearch.bestCosine(q, floatArrayOf(0.6f, 0.8f))!!, 1e-6f)
    }

    @Test fun bestCosineRejectsVectorsOfAnotherShape() {
        val q = floatArrayOf(1f, 0f)
        assertEquals(null, VectorSearch.bestCosine(q, floatArrayOf(1f, 0f, 0f)))
        assertEquals(null, VectorSearch.bestCosine(q, floatArrayOf()))
    }

    @Test fun topKSkipsMismatchedVectorsInsteadOfThrowing() {
        val q = floatArrayOf(1f, 0f)
        val entries = listOf(
            entry(1, "other model", floatArrayOf(1f, 0f, 0f)),
            entry(2, "long entry", floatArrayOf(0f, 1f, 0.9f, 0.1f)),
        )
        assertEquals(listOf(2L), VectorSearch.topK(q, entries, 5).map { it.first.id })
    }

    // HybridSearch

    private val q = floatArrayOf(1f, 0f)
    private fun at(sim: Float) = floatArrayOf(sim, kotlin.math.sqrt(1f - sim * sim))

    @Test fun unrelatedEntriesAreNotResults() {
        val entries = listOf(
            entry(1, "made pasta for dinner", at(0.30f)),
            entry(2, "long run by the river", at(0.45f)),
        )
        assertTrue(HybridSearch.rank("quantum physics", q, entries, 20, semanticFloor = 0.5f).isEmpty())
    }

    @Test fun relatedEntriesAreResultsWithoutSharingAWord() {
        val entries = listOf(
            entry(1, "felt anxious about the dissertation deadline", at(0.70f)),
            entry(2, "made pasta for dinner", at(0.30f)),
        )
        val r = HybridSearch.rank("thesis stress", q, entries, 20, semanticFloor = 0.5f)
        assertEquals(listOf(1L), r.map { it.entry.id })
    }

    @Test fun aWeakTailBehindAStrongAnswerIsDropped() {
        val entries = listOf(
            entry(1, "the answer", at(0.80f)),
            entry(2, "same league", at(0.70f)),
            entry(3, "above the floor but far behind", at(0.60f)),
        )
        val r = HybridSearch.rank("zzqx", q, entries, 20, semanticFloor = 0.5f, semanticMargin = 0.12f)
        assertEquals(listOf(1L, 2L), r.map { it.entry.id })
    }

    @Test fun aLiteralWordMatchIsAResultEvenBelowTheFloor() {
        // A name: the model has no idea who Priya is, the words do.
        val entries = listOf(entry(1, "lunch with Priya downtown", at(0.20f)))
        val r = HybridSearch.rank("Priya", q, entries, 20, semanticFloor = 0.5f)
        assertEquals(listOf(1L), r.map { it.entry.id })
    }

    @Test fun aLiteralMatchOutranksAMerelyRelatedEntry() {
        val entries = listOf(
            entry(1, "thinking about grad school applications", at(0.62f)),
            entry(2, "sent the thesis draft to my advisor", at(0.60f)),
        )
        val r = HybridSearch.rank("thesis", q, entries, 20, semanticFloor = 0.5f)
        assertEquals(listOf(2L, 1L), r.map { it.entry.id })
    }

    @Test fun entriesWithoutAVectorAreFoundByTheirWords() {
        // Just imported, or edited a moment ago: no vector yet.
        val entries = listOf(
            entry(1, "booked the dentist for Tuesday", null),
            entry(2, "something else entirely", null),
            entry(3, "loosely related", at(0.55f)),
        )
        val r = HybridSearch.rank("dentist", q, entries, 20, semanticFloor = 0.5f)
        // Ranked as if on the floor plus its keyword credit: ahead of a weak semantic hit.
        assertEquals(listOf(1L, 3L), r.map { it.entry.id })
    }

    @Test fun withoutAQueryVectorItIsKeywordSearch() {
        val entries = listOf(
            entry(1, "thesis defense went fine", at(0.9f)),
            entry(2, "made pasta", at(0.9f)),
        )
        val r = HybridSearch.rank("thesis", null, entries, 20)
        assertEquals(listOf(1L), r.map { it.entry.id })
    }

    @Test fun resultsAreCappedAtK() {
        val entries = (1..30L).map { entry(it, "thesis note $it", at(0.6f)) }
        assertEquals(20, HybridSearch.rank("thesis", q, entries, 20).size)
    }

    @Test fun blankQueryWithoutVectorReturnsNothing() {
        assertTrue(HybridSearch.rank("  ", null, listOf(entry(1, "anything")), 20).isEmpty())
    }
}
