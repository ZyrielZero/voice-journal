package dev.zyriel.voicejournal.bench

/**
 * Accuracy core for transcription benchmarks. Pure Kotlin for the same
 * reason [Bench] is: the scoring itself is unit-tested, so a WER number in
 * a committed benchmark file is trustworthy rather than hand-waved.
 *
 * Word Error Rate is the standard ASR metric: the word-level edit distance
 * between a reference (what was actually said) and a hypothesis (what
 * whisper produced), divided by the reference length. Substitutions,
 * deletions, and insertions are reported separately because they inform
 * the model decision differently: heavy deletions point at over-aggressive
 * silence trimming, heavy substitutions at a model too small to resolve
 * the words. A single blended WER would hide that.
 */
object WordErrorRate {

    /**
     * One alignment between a reference and a hypothesis.
     *
     * @property substitutions reference word replaced by a different word
     * @property deletions reference word absent from the hypothesis
     * @property insertions hypothesis word with no reference counterpart
     * @property referenceWords tokens in the normalized reference; the WER denominator
     */
    data class Alignment(
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val referenceWords: Int,
    ) {
        val errors: Int get() = substitutions + deletions + insertions

        /**
         * (S + D + I) / referenceWords. Can exceed 1.0: insertions are not
         * bounded by the reference length, so a hypothesis that is longer
         * and wronger than the reference scores above 100%.
         */
        val wer: Double get() = errors.toDouble() / referenceWords
    }

    /**
     * British spellings mapped to the American forms LibriSpeech references
     * use. Deliberately small: the common -our, -re, -ise and doubled-l
     * words, not a dictionary. Whisper writes "grey" for GRAY and "colour"
     * for COLOR depending on context, and counting those as substitutions
     * measures spelling convention, not hearing. Whisper's own
     * EnglishTextNormalizer does the same with a much larger list.
     */
    internal val BRITISH_TO_AMERICAN: Map<String, String> = mapOf(
        "grey" to "gray", "greys" to "grays", "greyish" to "grayish",
        "colour" to "color", "colours" to "colors", "coloured" to "colored",
        "colourist" to "colorist", "colourless" to "colorless",
        "favour" to "favor", "favours" to "favors", "favourite" to "favorite",
        "favourable" to "favorable", "honour" to "honor", "honours" to "honors",
        "honoured" to "honored", "labour" to "labor", "neighbour" to "neighbor",
        "neighbours" to "neighbors", "behaviour" to "behavior", "humour" to "humor",
        "harbour" to "harbor", "armour" to "armor", "vapour" to "vapor",
        "rumour" to "rumor", "savour" to "savor", "splendour" to "splendor",
        "endeavour" to "endeavor", "parlour" to "parlor", "valour" to "valor",
        "vigour" to "vigor", "odour" to "odor", "clamour" to "clamor",
        "centre" to "center", "theatre" to "theater", "metre" to "meter",
        "sombre" to "somber", "spectre" to "specter", "fibre" to "fiber",
        "realise" to "realize", "realised" to "realized", "recognise" to "recognize",
        "recognised" to "recognized", "organise" to "organize", "apologise" to "apologize",
        "travelled" to "traveled", "travelling" to "traveling", "traveller" to "traveler",
        "cancelled" to "canceled", "jewellery" to "jewelry", "defence" to "defense",
        "offence" to "offense", "plough" to "plow", "programme" to "program",
        "catalogue" to "catalog", "cheque" to "check", "grey's" to "gray's",
    )

    /**
     * Normalizes text to a comparable token list: lowercased, typographic
     * apostrophes (U+2019, U+2018, U+02BC) folded to ASCII, punctuation
     * dropped, whitespace collapsed, and British spellings mapped through
     * [BRITISH_TO_AMERICAN]. An apostrophe inside a token is kept so
     * contractions stay whole ("don't" is one token); leading and trailing
     * ones are stripped so a quoted 'word' still matches word. Without the
     * apostrophe fold, whisper's "don\u2019t" would split into two tokens
     * and score as two errors against DON'T.
     *
     * This is a plain normalizer, stricter than Whisper's
     * EnglishTextNormalizer: it does NOT normalize number words (whisper may
     * emit "20" where the reference reads "twenty"), hyphenation or
     * abbreviations. Keep digits out of the reference script and this stays
     * a clean measure of acoustic accuracy rather than formatting noise.
     */
    fun normalize(text: String): List<String> =
        text.lowercase()
            .replace('\u2019', '\'')
            .replace('\u2018', '\'')
            .replace('\u02BC', '\'')
            .replace(Regex("[^\\p{L}\\p{Nd}']+"), " ")
            .split(' ')
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }
            .map { BRITISH_TO_AMERICAN[it] ?: it }

    /**
     * 95% Wilson score interval for [errors] out of [words], returned as
     * (low, high) rates. WER is not strictly a binomial proportion (word
     * errors cluster within an utterance, and insertions are not bounded by
     * the reference length), so this understates the true uncertainty; it is
     * printed next to the WER so a small corpus is never read as precise.
     */
    fun wilsonInterval95(errors: Int, words: Int): Pair<Double, Double> {
        require(words > 0) { "no reference words" }
        val z = 1.959964
        val n = words.toDouble()
        val p = (errors.toDouble() / n).coerceAtMost(1.0)
        val denom = 1 + z * z / n
        val centre = (p + z * z / (2 * n)) / denom
        val half = z * kotlin.math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
        return (centre - half).coerceAtLeast(0.0) to (centre + half).coerceAtMost(1.0)
    }

    /** Convenience: normalize both sides, then [alignTokens]. */
    fun align(reference: String, hypothesis: String): Alignment =
        alignTokens(normalize(reference), normalize(hypothesis))

    /**
     * Levenshtein alignment over word tokens, with an operation breakdown.
     *
     * The total edit distance (and therefore the WER) is unique; the split
     * into S/D/I is not, when edits tie. Backtrace order is fixed as match,
     * then substitution, then deletion, then insertion, so the breakdown is
     * reproducible across runs and across machines.
     */
    fun alignTokens(reference: List<String>, hypothesis: List<String>): Alignment {
        require(reference.isNotEmpty()) { "reference has no words after normalization" }
        val n = reference.size
        val m = hypothesis.size

        // dp[i][j] = edit distance between reference[0 until i] and hypothesis[0 until j].
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i   // delete every remaining reference word
        for (j in 0..m) dp[0][j] = j   // insert every hypothesis word
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] = if (reference[i - 1] == hypothesis[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        var i = n
        var j = m
        var subs = 0
        var dels = 0
        var ins = 0
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && reference[i - 1] == hypothesis[j - 1] &&
                    dp[i][j] == dp[i - 1][j - 1] -> {
                    i--; j--                       // match
                }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    subs++; i--; j--               // substitution
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> {
                    dels++; i--                    // deletion: reference word dropped
                }
                else -> {
                    ins++; j--                     // insertion: extra hypothesis word
                }
            }
        }
        return Alignment(substitutions = subs, deletions = dels, insertions = ins, referenceWords = n)
    }
}
