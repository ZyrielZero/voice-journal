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

    @Test
    fun typographicApostropheMatchesAscii() {
        // Whisper often emits U+2019; without folding, "don\u2019t" became
        // two tokens and scored as two errors against DON'T.
        assertEquals(listOf("don't", "stop"), WordErrorRate.normalize("Don\u2019t stop"))
        assertEquals(listOf("time's"), WordErrorRate.normalize("\u2018time\u2019s\u2019"))
        val a = WordErrorRate.align("I DON'T KNOW TIME'S UP", "I don\u2019t know, time\u2019s up.")
        assertEquals(0, a.errors)
    }

    @Test
    fun britishSpellingsScoreAsAmerican() {
        val a = WordErrorRate.align(
            "OUT OF GRAY PAPER THEIR FAVORITE COLOR",
            "out of grey paper, their favourite colour",
        )
        assertEquals(0, a.errors)
        // The map is applied to the reference too, so either side may be British.
        assertEquals(listOf("center", "theater"), WordErrorRate.normalize("Centre theatre"))
    }

    @Test
    fun spellingMapDoesNotHideRealSubstitutions() {
        // COLORIST vs "colorless" is a genuine mishearing and must still count.
        val a = WordErrorRate.align("A GREAT COLORIST", "a great colourless")
        assertEquals(1, a.substitutions)
    }

    @Test
    fun wilsonIntervalMatchesReviewFigures() {
        // 10 errors in 222 words, as in the committed ce5f560 VAD-on row:
        // roughly 2.5% to 8.1%.
        val (lo, hi) = WordErrorRate.wilsonInterval95(10, 222)
        assertEquals(0.0247, lo, 0.001)
        assertEquals(0.0810, hi, 0.001)
        val (zlo, zhi) = WordErrorRate.wilsonInterval95(0, 50)
        assertEquals(0.0, zlo, 0.0)
        assertTrue(zhi > 0.0 && zhi < 0.1)
    }
}
