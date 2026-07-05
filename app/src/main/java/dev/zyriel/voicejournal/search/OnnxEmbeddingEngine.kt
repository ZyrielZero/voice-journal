package dev.zyriel.voicejournal.search

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * bge-small-en-v1.5 (int8 ONNX) via ONNX Runtime. 384-dim vectors.
 *
 * BGE specifics that are easy to get wrong:
 *  - Pooling is CLS token, not mean pooling.
 *  - Vectors must be L2-normalized before cosine ranking.
 *  - Queries (not documents) get a retrieval instruction prefix.
 *
 * Model/tokenizer bytes are injected so the class stays JVM-testable;
 * Android supplies them from assets.
 */
class OnnxEmbeddingEngine(
    modelBytes: ByteArray,
    vocab: WordPieceTokenizer,
) : EmbeddingEngine {

    companion object {
        const val MODEL_TAG = "bge-small-en-v1.5-q8"
        const val DIM = 384
        const val MAX_TOKENS = 256
        const val QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions())
    private val tokenizer = vocab
    private val mutex = Mutex()

    /** Embeds a document/entry. */
    override suspend fun embed(text: String): FloatArray? = run(text)

    /** Embeds a search query with the BGE retrieval prefix. */
    suspend fun embedQuery(text: String): FloatArray? = run(QUERY_PREFIX + text)

    private suspend fun run(text: String): FloatArray? = try {
        runInner(text)
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

    private suspend fun runInner(text: String): FloatArray = mutex.withLock {
        withContext(Dispatchers.Default) {
            val ids = tokenizer.encode(text, MAX_TOKENS)
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
                            l2Normalize(hidden[0][0].copyOf())   // CLS token
                        }
                    }
                }
            }
        }
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
