package dev.zyriel.voicejournal.search

import java.io.InputStream

/**
 * BERT WordPiece tokenizer (uncased) for bge-small-en-v1.5.
 *
 * Pure Kotlin so it's unit-tested on the JVM. Implements the standard
 * pipeline: lowercase, strip accents, split on whitespace and punctuation,
 * then greedy longest-match WordPiece with "##" continuation pieces.
 */
class WordPieceTokenizer(vocabStream: InputStream) {

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"
        const val PAD = "[PAD]"
        private const val MAX_WORD_CHARS = 100
    }

    private val vocab: Map<String, Int> = buildMap {
        vocabStream.bufferedReader().forEachLine { line ->
            if (line.isNotEmpty()) put(line, size)
        }
    }

    val clsId = vocab.getValue(CLS)
    val sepId = vocab.getValue(SEP)
    val padId = vocab.getValue(PAD)
    private val unkId = vocab.getValue(UNK)

    /** Returns token ids including [CLS] and [SEP], truncated to [maxLen]. */
    fun encode(text: String, maxLen: Int): LongArray {
        val ids = ArrayList<Long>(64)
        ids.add(clsId.toLong())
        outer@ for (word in basicTokenize(text)) {
            for (id in wordPiece(word)) {
                if (ids.size >= maxLen - 1) break@outer
                ids.add(id.toLong())
            }
        }
        ids.add(sepId.toLong())
        return ids.toLongArray()
    }

    private fun basicTokenize(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when {
                ch.code == 0 || ch.code == 0xFFFD || ch.isISOControl() && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> cleaned.append(ch)
            }
        }
        // Strip accents (NFD decomposition, drop combining marks)
        val normalized = java.text.Normalizer.normalize(cleaned, java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() } }
        for (ch in normalized) {
            when {
                ch.isWhitespace() -> flush()
                isPunct(ch) -> { flush(); out.add(ch.toString()) }
                else -> cur.append(ch)
            }
        }
        flush()
        return out
    }

    private fun isPunct(ch: Char): Boolean {
        val c = ch.code
        if ((c in 33..47) || (c in 58..64) || (c in 91..96) || (c in 123..126)) return true
        val type = Character.getType(ch)
        return type in intArrayOf(
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
        )
    }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > MAX_WORD_CHARS) return listOf(unkId)
        val pieces = ArrayList<Int>(4)
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[piece]
                if (id != null) { found = id; break }
                end--
            }
            if (found == -1) return listOf(unkId)  // whole word becomes UNK, per BERT reference
            pieces.add(found)
            start = end
        }
        return pieces
    }
}
