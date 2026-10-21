package dev.zyriel.voicejournal.search

import java.io.InputStream

/**
 * BERT WordPiece tokenizer (uncased) for bge-small-en-v1.5.
 *
 * Pure Kotlin so it's unit-tested on the JVM. Implements the standard
 * pipeline: lowercase, strip accents, split on whitespace and punctuation,
 * split every CJK ideograph into its own word (as HF BasicTokenizer does),
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

    // Ids are line indices, as in HF's load_vocab: a blank line still
    // consumes its id, so it cannot shift every later token. A duplicate
    // token would silently remap an id, so it fails loudly instead.
    private val vocab: Map<String, Int> = buildMap {
        var index = 0
        vocabStream.bufferedReader().forEachLine { line ->
            if (line.isNotEmpty()) {
                require(put(line, index) == null) { "duplicate vocab token '$line' at line ${index + 1}" }
            }
            index++
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
        // Walks code points, not UTF-16 chars: CJK Extension B and later
        // live in the supplementary planes as surrogate pairs.
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            when {
                Character.isWhitespace(cp) -> flush()
                // HF BasicTokenizer spaces every CJK ideograph, so each one
                // is its own word and maps to its per-character vocab id.
                isCjk(cp) -> { flush(); out.add(String(Character.toChars(cp))) }
                cp < 0x10000 && isPunct(cp.toChar()) -> { flush(); out.add(cp.toChar().toString()) }
                else -> cur.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        flush()
        return out
    }

    /** The CJK Unified Ideograph blocks of HF's `_is_chinese_char`. */
    internal fun isCjk(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF ||
            cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F ||
            cp in 0x2B820..0x2CEAF || cp in 0x2F800..0x2FA1F

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
        // Candidate cut points are code-point boundaries, so a piece never
        // splits a surrogate pair and the length cap counts characters the
        // way the reference (Python str) does.
        val bounds = IntArray(word.codePointCount(0, word.length) + 1)
        run {
            var k = 0
            var off = 0
            while (off < word.length) { bounds[k++] = off; off += Character.charCount(word.codePointAt(off)) }
            bounds[k] = word.length
        }
        val chars = bounds.size - 1
        if (chars > MAX_WORD_CHARS) return listOf(unkId)
        val pieces = ArrayList<Int>(4)
        var start = 0
        while (start < chars) {
            var end = chars
            var found = -1
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + word.substring(bounds[start], bounds[end])
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
