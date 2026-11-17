package dev.zyriel.voicejournal.search

import dev.zyriel.voicejournal.data.JournalEntry
import kotlin.math.sqrt

/**
 * Embedding abstraction, implemented by [OnnxEmbeddingEngine] (gte-base
 * int8 via ONNX Runtime). When the engine fails to initialize or an embed
 * call fails, callers receive null and search falls back to keywords
 * ([HybridSearch] with no query vector); the null return is the contract,
 * not a hypothetical.
 */
interface EmbeddingEngine {
    /**
     * Returns one L2-normalized vector, or several back to back for a text
     * longer than the model's window (see [VectorSearch.bestCosine]), or
     * null if the engine is unavailable.
     */
    suspend fun embed(text: String): FloatArray?

    /**
     * Embeds a search query: always a single vector. Models that want a
     * retrieval instruction on the query side add it here.
     */
    suspend fun embedQuery(text: String): FloatArray? = embed(text)
}

/** Brute-force cosine top-k. Pure Kotlin, JVM-tested. Assumes vectors may be unnormalized. */
object VectorSearch {

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
        return cosineAt(a, b, 0)
    }

    /** Cosine of [a] against the [a].size floats of [b] starting at [offset]. */
    private fun cosineAt(a: FloatArray, b: FloatArray, offset: Int): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            val y = b[offset + i]
            dot += a[i] * y; na += a[i] * a[i]; nb += y * y
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * Similarity of [query] to a stored entry vector. A long entry stores
     * one vector per window, back to back, so [stored] may be any whole
     * multiple of the query's length; the entry scores as its best window,
     * because a query is about one passage, not the average of an entry.
     * Null when the sizes do not line up (a vector from another model).
     */
    fun bestCosine(query: FloatArray, stored: FloatArray): Float? {
        val dim = query.size
        if (dim == 0 || stored.isEmpty() || stored.size % dim != 0) return null
        var best = -1f
        var off = 0
        while (off < stored.size) {
            val c = cosineAt(query, stored, off)
            if (c > best) best = c
            off += dim
        }
        return best
    }

    fun topK(query: FloatArray, entries: List<JournalEntry>, k: Int): List<Pair<JournalEntry, Float>> =
        entries.asSequence()
            .mapNotNull { e -> e.embedding?.let { v -> bestCosine(query, v)?.let { e to it } } }
            .sortedByDescending { it.second }
            .take(k)
            .toList()
}

/**
 * Token-overlap keyword scoring. Scores by the fraction of query tokens
 * present, with a bonus for exact phrase hits. Pure Kotlin, JVM-tested.
 */
object KeywordSearch {

    /** Added to the coverage fraction when the whole query appears verbatim. */
    const val PHRASE_BONUS = 0.5f

    /**
     * Words that say nothing about what an entry is about. A spoken query
     * like "what did I say about my thesis" would otherwise match every
     * entry containing "my". Dropped from the query only, and only when
     * something else is left: a query made of nothing but these keeps them.
     */
    private val STOPWORDS = setOf(
        "a", "an", "and", "the", "or", "but", "if", "of", "to", "in", "on", "at", "by", "for", "with",
        "about", "from", "into", "as", "is", "am", "are", "was", "were", "be", "been", "being", "it",
        "its", "it's", "this", "that", "these", "those", "i", "i'm", "i've", "i'd", "me", "my", "mine",
        "we", "our", "you", "your", "he", "she", "they", "them", "his", "her", "their", "do", "did",
        "does", "have", "has", "had", "so", "not", "no", "what", "when", "where", "who", "why", "how",
        "which", "say", "said", "talk", "talked", "there", "then", "than", "just", "some", "any", "can",
        "could", "would", "should", "will", "up", "out",
    )

    private val SPLIT = Regex("[^a-z0-9']+")

    private fun tokens(s: String): List<String> =
        s.lowercase().split(SPLIT).filter { it.length > 1 }

    /** A query prepared once and scored against many entries. */
    class Query internal constructor(internal val tokens: List<String>, internal val phrase: String) {
        val isEmpty: Boolean get() = tokens.isEmpty()
    }

    fun prepare(query: String): Query {
        val all = tokens(query)
        val content = all.filter { it !in STOPWORDS }
        return Query(content.ifEmpty { all }, query.trim().lowercase())
    }

    /** 0 when no query token occurs; otherwise coverage in (0, 1], plus [PHRASE_BONUS]. */
    fun score(q: Query, transcript: String): Float {
        if (q.isEmpty) return 0f
        val lower = transcript.lowercase()
        val t = lower.split(SPLIT).toHashSet()
        val hits = q.tokens.count { it in t }
        if (hits == 0) return 0f
        var score = hits.toFloat() / q.tokens.size
        if (q.phrase.length > 3 && lower.contains(q.phrase)) score += PHRASE_BONUS
        return score
    }

    fun rank(query: String, entries: List<JournalEntry>, k: Int): List<Pair<JournalEntry, Float>> {
        val q = prepare(query)
        if (q.isEmpty) return emptyList()
        return entries.asSequence()
            .map { e -> e to score(q, e.transcript) }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(k)
            .toList()
    }
}

/**
 * What the search box runs: meaning and words together.
 *
 * Vector search alone has three gaps. It always returns its k nearest
 * entries, however unrelated, so a query with no real answer never shows
 * "nothing matches". It cannot see an entry whose vector has not been
 * computed yet (a fresh import, a transcript edited a moment ago). And an
 * exact word the user remembers saying, a name above all, gets no credit
 * for being there verbatim. So:
 *
 *  - An entry is a result if it is related by meaning or contains a query
 *    word. Related by meaning means its similarity clears
 *    [SEMANTIC_FLOOR] and is either within [SEMANTIC_MARGIN] of the best
 *    entry's for this query or above [SEMANTIC_STRONG] outright. Entries
 *    with no vector can still match on words.
 *  - Results are ordered by similarity plus a credit for the keyword
 *    score ([keywordCredit]), so a literal hit outranks a merely related
 *    entry of about the same similarity.
 *
 * With no query vector (the engine is unavailable) this is keyword search.
 * Pure Kotlin, JVM-tested.
 */
object HybridSearch {

    /**
     * Lowest similarity that can count as related. gte-base similarities
     * sit in a very narrow band: on the bundled int8 model a short query
     * scores about 0.70 to 0.80 against entries that have nothing to do
     * with it and 0.74 to 0.89 against the ones a person would expect
     * back (ConceptSearchTest and SearchCalibrationTest print the tables).
     * The ranges overlap, so no single number separates them. This one
     * drops the clearly unrelated and lets about half of the queries
     * about a subject the journal never mentions come back empty, at the
     * cost of the weakest tenth of real matches. It is a property of this
     * exact model and must be re-measured if the model changes: all three
     * numbers here were about 0.25 lower for bge-small.
     */
    const val SEMANTIC_FLOOR = 0.76f

    /**
     * How far below the query's best entry a result may sit. The floor
     * alone would let through a tail of weak matches whenever something
     * scores well; this keeps only what is in the same league as the best
     * answer. Wide enough for the third of three on-topic entries: for
     * "physics" they score 0.81, 0.80 and 0.76.
     */
    const val SEMANTIC_MARGIN = 0.05f

    /**
     * Similarity that counts as related however far ahead the best entry
     * is. Without it one very close match hides a second good one: for
     * "stress about my dissertation" the two thesis entries score 0.89
     * and 0.81, further apart than the margin, and both belong.
     */
    const val SEMANTIC_STRONG = 0.80f

    /**
     * How much a full keyword match adds to an entry's ranking score.
     * Several times the spread between related entries, so an entry that
     * contains the query's words leads the ones that are only about them.
     */
    const val KEYWORD_WEIGHT = 0.25f

    /**
     * What a keyword score adds to the ranking score. A full match adds
     * [KEYWORD_WEIGHT] (more with the phrase bonus); a partial one adds
     * its coverage squared, so one incidental word out of several cannot
     * lift an entry over the one the query is about. Measured on a phone:
     * "something I cooked" ranked an entry about tomatoes (similarity
     * 0.79, it says "something") above the stew (0.87) when half the
     * words earned half the weight.
     */
    internal fun keywordCredit(kw: Float): Float = KEYWORD_WEIGHT * kw * kw.coerceAtMost(1f)

    /**
     * True for a transcript that is one bracketed note and nothing else:
     * the app's own "(no speech detected)" and "(recording could not be
     * transcribed)", or a lone whisper annotation like "[BLANK_AUDIO]".
     * Such an entry says nothing, yet its vector sits close to every
     * short query (0.76 to 0.80 on a phone, above entries on the subject),
     * so it is never related by meaning. Its words still match.
     */
    internal fun isPlaceholder(transcript: String): Boolean {
        val t = transcript.trim()
        if (t.length < 2) return false
        val close = when (t.first()) { '(' -> ')'; '[' -> ']'; else -> return false }
        return t.indexOf(close) == t.length - 1
    }

    data class Hit(val entry: JournalEntry, val score: Float, val similarity: Float?, val keyword: Float)

    fun rank(
        query: String,
        queryVector: FloatArray?,
        entries: List<JournalEntry>,
        k: Int,
        semanticFloor: Float = SEMANTIC_FLOOR,
        semanticMargin: Float = SEMANTIC_MARGIN,
        semanticStrong: Float = SEMANTIC_STRONG,
        /**
         * When set, only vectors stored under this model tag are compared.
         * After a model change the old vectors stay in the database until
         * the backfill replaces them, and a long entry's old vector can
         * happen to have a length the new model's would; its similarity
         * would be noise. Such entries match on words until re-embedded.
         */
        modelTag: String? = null,
    ): List<Hit> {
        val q = KeywordSearch.prepare(query)
        if (q.isEmpty && queryVector == null) return emptyList()

        val sims: List<Float?> = entries.map { e ->
            if (queryVector == null || (modelTag != null && e.embeddingModel != modelTag)) null
            else if (isPlaceholder(e.transcript)) null
            else e.embedding?.let { VectorSearch.bestCosine(queryVector, it) }
        }
        val best = sims.maxOfOrNull { it ?: -1f } ?: -1f
        val cut = best - semanticMargin

        val hits = ArrayList<Hit>()
        entries.forEachIndexed { i, e ->
            val sim = sims[i]
            val kw = KeywordSearch.score(q, e.transcript)
            val related = sim != null && sim >= semanticFloor && (sim >= cut || sim >= semanticStrong)
            if (!related && kw <= 0f) return@forEachIndexed
            // An entry with no vector yet (or a placeholder) matched on
            // words alone. It is ranked as if it sat exactly on the floor,
            // so it competes with related entries instead of trailing
            // every one of them.
            val base = sim ?: if (queryVector != null) semanticFloor else 0f
            hits += Hit(e, base.coerceAtLeast(0f) + keywordCredit(kw), sim, kw)
        }
        hits.sortByDescending { it.score }
        return if (hits.size > k) hits.subList(0, k).toList() else hits
    }
}
