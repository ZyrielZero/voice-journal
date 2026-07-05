package dev.zyriel.voicejournal.data

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression: JournalEntry used id-only equality, so a list containing an
 * edited entry compared equal to the pre-edit list. StateFlow drops emissions
 * equal to the current value, so transcript edits saved to the DB but never
 * reached the screen.
 */
class JournalEntryEqualityTest {

    private fun entry(id: Long = 1L) = JournalEntry(
        id = id,
        transcript = "original text",
        timestampMs = 1000L,
        audioPath = "/x/entry.wav",
        embedding = floatArrayOf(0.1f, 0.2f),
        embeddingModel = "bge-small-en-v1.5-q8",
    )

    @Test
    fun editedTranscriptMakesEntriesUnequal() {
        assertNotEquals(entry(), entry().copy(transcript = "edited text"))
    }

    @Test
    fun sameContentIsEqualEvenWithDistinctArrayInstances() {
        val a = entry()
        val b = entry().copy(embedding = floatArrayOf(0.1f, 0.2f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun embeddingChangeIsVisible() {
        assertNotEquals(entry(), entry().copy(embedding = floatArrayOf(0.9f, 0.9f)))
        assertNotEquals(entry(), entry().copy(embedding = null))
    }

    @Test
    fun stateFlowNoLongerSwallowsAnEditedList() {
        // The exact mechanism that ate the edit: value assignment on a
        // StateFlow is a no-op when the new value equals the old one.
        val flow = MutableStateFlow(listOf(entry()))
        flow.value = listOf(entry().copy(transcript = "edited text"))
        assertEquals("edited text", flow.value.single().transcript)
    }
}
