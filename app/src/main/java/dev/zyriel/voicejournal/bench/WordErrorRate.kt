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
     * Normalizes text to a comparable token list: lowercased, punctuation
     * dropped, whitespace collapsed. An apostrophe inside a token is kept so
     * contractions stay whole ("don't" is one token); leading and trailing
     * ones are stripped so a quoted 'word' still matches word.
     *
     * Deliberately does NOT normalize number words: whisper may emit "20"
     * where the reference reads "twenty", and reconciling those is a
     * separate problem with its own failure modes. Keep digits out of the
     * reference script and this stays a clean measure of acoustic accuracy
     * rather than formatting noise.
     */
    fun normalize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}']+"), " ")
            .split(' ')
            .map { it.trim('\'') }
            .filter { it.isNotEmpty() }

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
