package dev.zyriel.voicejournal.search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** CJK splitting and vocab-id rules, matched to HF BasicTokenizer + load_vocab. */
class WordPieceCjkTest {

    // Line index = id. Line 9 is blank on purpose: it still consumes id 9.
    private val vocabLines = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "日", "本", "語", "##本", "##語", "", "hello", "𠀀", "##lo", "hel",
    )
    private fun tok(lines: List<String> = vocabLines) =
        WordPieceTokenizer(lines.joinToString("\n").byteInputStream())

    private fun ids(text: String) = tok().encode(text, 64).drop(1).dropLast(1) // strip CLS/SEP

    @Test
    fun cjkRunSplitsIntoPerCharacterIdsNotContinuationPieces() {
        assertEquals(listOf(4L, 5L, 6L), ids("日本語"))
    }

    @Test
    fun cjkAdjacentToLatinIsSplitOffBothSides() {
        assertEquals(listOf(10L, 4L, 5L, 10L), ids("hello日本hello"))
    }

    @Test
    fun supplementaryPlaneIdeographIsOneWord() {
        // U+20000 (CJK Extension B) is a surrogate pair in UTF-16.
        assertEquals(listOf(11L, 4L), ids("𠀀日"))
    }

    @Test
    fun blankVocabLineKeepsLaterIdsOnTheirLineIndex() {
        assertEquals(listOf(10L), ids("hello"))
        assertEquals("hel + ##lo still works", listOf(13L, 12L), tok(vocabLines.map { if (it == "hello") "[unused0]" else it }).encode("hello", 8).drop(1).dropLast(1))
    }

    @Test
    fun duplicateVocabLineFailsLoudly() {
        try {
            tok(vocabLines + "日")
            fail("duplicate token must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun realBgeVocabGivesPerCharacterIds() {
        val f = File("src/main/assets/models/bge_vocab.txt")
        if (!f.isFile) return // models are fetched at build time; skip when absent
        val t = WordPieceTokenizer(f.inputStream())
        // Ids checked against the vocab's own line numbers (0-based).
        assertArrayEquals(longArrayOf(t.clsId.toLong(), 1864, 1876, 1950, t.sepId.toLong()), t.encode("日本語", 16))
    }
}
