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
        val entries = listOf(
            entry(1, "thesis draft going slowly"),
            entry(2, "thesis defense scheduled, defense prep starts now"),
        )
        val r = KeywordSearch.rank("thesis defense", entries, 5)
        assertEquals(2L, r[0].first.id)
        assertTrue(r[0].second > r[1].second)
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
}
