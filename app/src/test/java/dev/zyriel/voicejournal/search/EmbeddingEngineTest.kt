package dev.zyriel.voicejournal.search

import dev.zyriel.voicejournal.TestPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the real vocab and the real int8 ONNX model bundled in assets,
 * using ONNX Runtime's JVM artifact. This verifies tokenization, inference,
 * CLS pooling, and normalization off-device. On-device latency remains a
 * separate, hardware-only question.
 */
class EmbeddingEngineTest {

    private fun asset(name: String) = TestPaths.mainAsset("models/$name")

    private fun tokenizer() = WordPieceTokenizer(asset("bge_vocab.txt").inputStream())

    private fun engine() = OnnxEmbeddingEngine(
        modelBytes = asset("bge_small_en_v15_q8.onnx").readBytes(),
        vocab = tokenizer(),
    )

    // Tokenizer

    @Test
    fun encodeWrapsWithClsAndSep() {
        val t = tokenizer()
        val ids = t.encode("hello world", maxLen = 16)
        assertEquals(t.clsId.toLong(), ids.first())
        assertEquals(t.sepId.toLong(), ids.last())
        assertEquals(4, ids.size) // [CLS] hello world [SEP]
    }

    @Test
    fun uncasedAndAccentsFold() {
        val t = tokenizer()
        assertEquals(t.encode("Hello", 8).toList(), t.encode("hello", 8).toList())
        assertEquals(t.encode("café", 8).toList(), t.encode("cafe", 8).toList())
    }

    /**
     * Out-of-vocabulary handling, both branches. "journaling" is not a whole
     * vocab entry, so it must come back as two or more subword pieces with
     * no [UNK]; a word past the tokenizer's length cap must come back as a
     * single [UNK]. (The old version asserted only size > 3, which an [UNK]
     * result would have failed despite the test's name.)
     */
    @Test
    fun unknownWordSplitsToSubwordsOrUnk() {
        val t = tokenizer()
        val unk = asset("bge_vocab.txt").readLines().indexOf("[UNK]").toLong()
        assertTrue("vocab has no [UNK]", unk >= 0)

        val pieces = t.encode("journaling", 16).toList().drop(1).dropLast(1)
        assertTrue("expected subword pieces, got $pieces", pieces.size >= 2)
        assertTrue("subword split must not produce [UNK]: $pieces", unk !in pieces)

        val overlong = t.encode("a".repeat(150), 16).toList().drop(1).dropLast(1)
        assertEquals(listOf(unk), overlong)
    }

    @Test
    fun truncationRespectsMaxLen() {
        val t = tokenizer()
        val long = (1..500).joinToString(" ") { "word" }
        assertEquals(32, t.encode(long, 32).size)
    }

    // End-to-end inference on the real model

    @Test
    fun embeddingHasCorrectDimensionAndUnitNorm() = runBlocking {
        val e = engine()
        val v = e.embed("I worked on my thesis introduction today.")
        assertNotNull(v)
        assertEquals(OnnxEmbeddingEngine.DIM, v!!.size)
        val norm = Math.sqrt(v.sumOf { (it * it).toDouble() })
        assertEquals(1.0, norm, 1e-3)
        e.close()
    }

    @Test
    fun semanticNeighborsBeatUnrelatedText() = runBlocking {
        val e = engine()
        val thesis = e.embed("I made progress on my thesis draft and talked to my advisor.")!!
        val query = e.embedQuery("what did I say about my thesis")!!
        val groceries = e.embed("Bought milk, eggs, and bread at the store.")!!

        val relevant = VectorSearch.cosine(query, thesis)
        val irrelevant = VectorSearch.cosine(query, groceries)
        assertTrue("expected $relevant > $irrelevant", relevant > irrelevant)
        e.close()
    }

    @Test
    fun paraphraseOutranksKeywordlessOverlap() = runBlocking {
        val e = engine()
        // No shared keywords with the query: this is what keyword search cannot do.
        val paraphrase = e.embed("Felt anxious about the dissertation deadline coming up.")!!
        val distractor = e.embed("The weather was nice so I went for a run.")!!
        val query = e.embedQuery("stress about my thesis")!!

        assertTrue(
            VectorSearch.cosine(query, paraphrase) > VectorSearch.cosine(query, distractor)
        )
        e.close()
    }

    // Long entries: one vector per window

    @Test
    fun shortTextIsOneChunkIdenticalToEncode() {
        val t = tokenizer()
        val text = "I worked on my thesis introduction today."
        val chunks = t.encodeChunks(text, maxLen = 256, overlap = 32, maxChunks = 16)
        assertEquals(1, chunks.size)
        assertEquals(t.encode(text, 256).toList(), chunks[0].toList())
        // Empty text is still one well-formed window: [CLS] [SEP].
        assertEquals(listOf(t.clsId.toLong(), t.sepId.toLong()), t.encodeChunks("", 256, 32, 16).single().toList())
    }

    @Test
    fun longTextIsCutIntoOverlappingWindowsThatCoverIt() {
        val t = tokenizer()
        val words = (1..100).map { "word" } + listOf("zebra") + (1..100).map { "word" }
        val all = t.encode(words.joinToString(" "), 10_000).toList().drop(1).dropLast(1)
        val chunks = t.encodeChunks(words.joinToString(" "), maxLen = 34, overlap = 8, maxChunks = 100)

        assertTrue(chunks.size > 1)
        for (c in chunks) {
            assertTrue(c.size <= 34)
            assertEquals(t.clsId.toLong(), c.first())
            assertEquals(t.sepId.toLong(), c.last())
        }
        // Stitching the windows back together, minus each overlap, gives the text.
        val stitched = ArrayList<Long>()
        chunks.forEachIndexed { i, c ->
            val body = c.toList().drop(1).dropLast(1)
            stitched += if (i == 0) body else body.drop(8)
        }
        assertEquals(all, stitched)
    }

    @Test
    fun chunkCountIsCapped() {
        val t = tokenizer()
        val long = (1..5_000).joinToString(" ") { "word" }
        assertEquals(3, t.encodeChunks(long, maxLen = 34, overlap = 8, maxChunks = 3).size)
    }

    @Test
    fun aLongEntryIsFoundByWhatItSaysNearTheEnd() = runBlocking {
        val e = engine()
        val filler = "The weather was mild and I spent the morning tidying the kitchen and sorting old mail. "
        val ending = "In the evening I finally booked the flights to Lisbon for our anniversary trip."
        val long = filler.repeat(40) + ending

        val stored = e.embed(long)!!
        assertTrue("expected several windows, got ${stored.size / OnnxEmbeddingEngine.DIM}",
            stored.size > OnnxEmbeddingEngine.DIM && stored.size % OnnxEmbeddingEngine.DIM == 0)

        val query = e.embedQuery("booking flights to Lisbon")!!
        assertEquals(OnnxEmbeddingEngine.DIM, query.size)
        val whole = VectorSearch.bestCosine(query, stored)!!
        // What the old single-vector embedding saw: only the first window.
        val firstWindowOnly = VectorSearch.cosine(query, stored.copyOfRange(0, OnnxEmbeddingEngine.DIM))
        assertTrue("end of entry must be searchable: best=$whole first=$firstWindowOnly", whole > firstWindowOnly + 0.1f)
        e.close()
    }

    @Test
    fun aShortEntryIsStillASingleVector() = runBlocking {
        val e = engine()
        assertEquals(OnnxEmbeddingEngine.DIM, e.embed("Short entry.")!!.size)
        e.close()
    }
}
