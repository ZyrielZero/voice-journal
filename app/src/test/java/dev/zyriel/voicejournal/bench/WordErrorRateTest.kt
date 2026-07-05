package dev.zyriel.voicejournal.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WordErrorRateTest {

    @Test
    fun identicalTranscriptScoresZero() {
        val a = WordErrorRate.align(
            "the harbor lights blur in the rain",
            "the harbor lights blur in the rain",
        )
        assertEquals(0, a.errors)
        assertEquals(0.0, a.wer, 0.0)
        assertEquals(7, a.referenceWords)
    }

    @Test
    fun oneWrongWordIsOneSubstitution() {
        val a = WordErrorRate.align("the lamp glows amber tonight", "the lamp glows umber tonight")
        assertEquals(1, a.substitutions)
        assertEquals(0, a.deletions)
        assertEquals(0, a.insertions)
        assertEquals(0.2, a.wer, 1e-9)
    }

    @Test
    fun droppedWordIsADeletion() {
        val a = WordErrorRate.align("i walked the long road home", "i walked the road home")
        assertEquals(0, a.substitutions)
        assertEquals(1, a.deletions)
        assertEquals(0, a.insertions)
        assertEquals(6, a.referenceWords)
    }

    @Test
    fun extraWordIsAnInsertion() {
        val a = WordErrorRate.align("close the window", "close the old window")
        assertEquals(0, a.substitutions)
        assertEquals(0, a.deletions)
        assertEquals(1, a.insertions)
        assertEquals(3, a.referenceWords)
    }

    @Test
    fun punctuationAndCaseAreIgnored() {
        val a = WordErrorRate.align("Rain. Wind. Then quiet.", "rain wind then quiet")
        assertEquals(0, a.errors)
        assertEquals(4, a.referenceWords)
    }

    @Test
    fun contractionsSurviveButSurroundingQuotesDoNot() {
        assertEquals(listOf("don't", "go"), WordErrorRate.normalize("'Don't' go!"))
    }

    @Test
    fun emptyHypothesisIsAllDeletions() {
        val a = WordErrorRate.align("three small words", "")
        assertEquals(3, a.deletions)
        assertEquals(1.0, a.wer, 0.0)
    }

    @Test
    fun insertionsCanPushWerAboveOne() {
        val a = WordErrorRate.align("silence", "silence and then a lot more")
        assertEquals(1, a.referenceWords)
        assertTrue("wer should exceed 1.0, was ${a.wer}", a.wer > 1.0)
    }

    @Test
    fun emptyReferenceThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            WordErrorRate.align("   ...   ", "anything")
        }
    }

    @Test
    fun mixedErrorsCountSeparately() {
        // "quick" dropped (deletion), "lazy" -> "sleepy" (substitution),
        // "very" added (insertion). Three errors over six reference words.
        val a = WordErrorRate.align(
            "the quick fox jumps over lazy",
            "the fox jumps over very sleepy",
        )
        assertEquals(1, a.deletions)
        assertEquals(1, a.substitutions)
        assertEquals(1, a.insertions)
        assertEquals(6, a.referenceWords)
        assertEquals(0.5, a.wer, 1e-9)
    }
}
