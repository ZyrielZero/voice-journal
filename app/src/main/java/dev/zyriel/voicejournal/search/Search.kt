package dev.zyriel.voicejournal.search

import dev.zyriel.voicejournal.data.JournalEntry
import kotlin.math.sqrt

/**
 * Embedding abstraction. The LiteRT/EmbeddingGemma implementation lands when
 * the (license-gated) model and tokenizer are integrated; until then the app
 * ships without an engine and search uses [KeywordSearch].
 */
interface EmbeddingEngine {
    /** Returns an L2-normalized vector, or null if the engine is unavailable. */
    suspend fun embed(text: String): FloatArray?
}

/** Brute-force cosine top-k. Pure Kotlin, JVM-tested. Assumes vectors may be unnormalized. */
object VectorSearch {

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0f || nb == 0f) return 0f
        return dot / (sqrt(na) * sqrt(nb))
    }

    fun topK(query: FloatArray, entries: List<JournalEntry>, k: Int): List<Pair<JournalEntry, Float>> =
        entries.asSequence()
            .mapNotNull { e -> e.embedding?.let { e to cosine(query, it) } }
            .sortedByDescending { it.second }
            .take(k)
            .toList()
}

/**
 * Token-overlap keyword search: the working fallback until semantic search
 * is active. Scores by fraction of query tokens present, with a bonus for
 * exact phrase hits. Pure Kotlin, JVM-tested.
 */
object KeywordSearch {

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length > 1 }

    fun rank(query: String, entries: List<JournalEntry>, k: Int): List<Pair<JournalEntry, Float>> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        val phrase = query.trim().lowercase()
        return entries.asSequence()
            .map { e ->
                val t = tokens(e.transcript).toHashSet()
                var score = q.count { it in t }.toFloat() / q.size
                if (phrase.length > 3 && e.transcript.lowercase().contains(phrase)) score += 0.5f
                e to score
            }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(k)
            .toList()
    }
}
