package dev.zyriel.voicejournal.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * gte-base (int8 ONNX) via ONNX Runtime. 768-dim vectors.
 *
 * It replaced bge-small-en-v1.5 because it is much better at finding an
 * entry by what it is about when the entry never says the word: "physics"
 * for a ramble about clocks on fast ships, "poems" for a recited sonnet
 * (ConceptSearchTest holds the comparison set and the bar).
 *
 * GTE specifics that are easy to get wrong, each the opposite of BGE:
 *  - Pooling is the mean over all tokens, not the CLS token.
 *  - Queries and documents are embedded the same way; there is no
 *    retrieval instruction prefix.
 *  - Vectors must still be L2-normalized before cosine ranking.
 *
 * The model and tokenizer are injected so the class stays JVM-testable.
 * Android passes the model as a read-only mapping of the APK asset
 * ([ModelAssets.map]): at 110 MB it must not pass through the Java heap,
 * where reading it into an array costs twice its size for a moment.
 */
class OnnxEmbeddingEngine private constructor(
    vocab: WordPieceTokenizer,
    open: (OrtEnvironment, OrtSession.SessionOptions) -> OrtSession,
) : EmbeddingEngine {

    /** [model] must be a direct buffer (a file mapping); ONNX Runtime reads it in place. */
    constructor(model: ByteBuffer, vocab: WordPieceTokenizer) :
        this(vocab, { env, opts -> env.createSession(model, opts) })

    /** For tests and small models: the bytes live on the Java heap. */
    constructor(modelBytes: ByteArray, vocab: WordPieceTokenizer) :
        this(vocab, { env, opts -> env.createSession(modelBytes, opts) })

    companion object {
        /**
         * Stored with every vector; the backfill re-embeds rows whose tag
         * differs, which is how a journal embedded by an older build (or
         * an older model: bge-small, 384 dims) moves over at next launch.
         * "+chunk1" is one vector per [MAX_TOKENS] window.
         */
        const val MODEL_TAG = "gte-base-q8+chunk1"
        const val DIM = 768
        const val MAX_TOKENS = 256
        /** Ids shared by neighbouring windows of a long entry. */
        const val CHUNK_OVERLAP = 32
        /**
         * Windows per entry. 16 windows cover about 3,400 tokens, more than
         * a 15-minute entry at a fast speaking rate, and cap the stored
         * vector at 16 x 768 floats (about 49 KB).
         */
        const val MAX_CHUNKS = 16
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    // Intra-op threads pinned to 2..4: ORT's default sizes the pool to every
    // core, which on big.LITTLE parts drags the little cores into each op.
    // Options are only read at session creation, so they are closed at once.
    private val session: OrtSession = OrtSession.SessionOptions().use { opts ->
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        open(env, opts)
    }
    private val tokenizer = vocab
    private val mutex = Mutex()

    /**
     * Embeds a document/entry. The model reads at most [MAX_TOKENS] tokens,
     * so a longer entry is embedded as overlapping windows and the result
     * is their vectors back to back ([DIM] floats each). A short entry is
     * one window and one vector, as before. All or nothing: if any window
     * fails the entry gets no vector and the backfill retries it.
     */
    override suspend fun embed(text: String): FloatArray? = guarded {
        val chunks = tokenizer.encodeChunks(text, MAX_TOKENS, CHUNK_OVERLAP, MAX_CHUNKS)
        if (chunks.size == 1) return@guarded infer(chunks[0])
        val out = FloatArray(chunks.size * DIM)
        chunks.forEachIndexed { i, ids ->
            // The mutex is taken per window, so a search query can run
            // between the windows of a long entry instead of behind it.
            val v = infer(ids)
            check(v.size == DIM) { "model returned ${v.size} dims, expected $DIM" }
            v.copyInto(out, i * DIM)
        }
        out
    }

    /** Embeds a search query: one vector, truncated to [MAX_TOKENS]. */
    override suspend fun embedQuery(text: String): FloatArray? = guarded {
        infer(tokenizer.encode(text, MAX_TOKENS))
    }

    private suspend fun guarded(block: suspend () -> FloatArray): FloatArray? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The EmbeddingEngine contract is null-on-failure; every caller
        // (insert, backfill, query) already degrades gracefully on null.
        // Throwing instead turned a transient ORT hiccup into an uncaught
        // coroutine exception — a process death at launch when it happened
        // inside the backfill.
        null
    }

    private suspend fun infer(ids: LongArray): FloatArray = mutex.withLock {
        withContext(Dispatchers.Default) {
            val n = ids.size
            val shape = longArrayOf(1, n.toLong())
            val attention = LongArray(n) { 1L }
            val tokenTypes = LongArray(n) { 0L }

            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { inputIds ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(attention), shape).use { mask ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypes), shape).use { types ->
                        val inputs = buildMap {
                            put("input_ids", inputIds)
                            put("attention_mask", mask)
                            if ("token_type_ids" in session.inputNames) put("token_type_ids", types)
                        }
                        session.run(inputs).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val hidden = result[0].value as Array<Array<FloatArray>>
                            l2Normalize(meanPool(hidden[0]))
                        }
                    }
                }
            }
        }
    }

    /** Mean over every token. One text per call, so there is no padding to mask out. */
    private fun meanPool(tokens: Array<FloatArray>): FloatArray {
        val out = FloatArray(tokens[0].size)
        for (t in tokens) for (i in out.indices) out[i] += t[i]
        val inv = 1f / tokens.size
        for (i in out.indices) out[i] *= inv
        return out
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        if (sum == 0f) return v
        val inv = 1f / sqrt(sum)
        for (i in v.indices) v[i] *= inv
        return v
    }

    fun close() {
        session.close()
    }
}
