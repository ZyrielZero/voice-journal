package dev.zyriel.voicejournal.bench

import android.content.Context
import android.os.Build
import dev.zyriel.voicejournal.BuildConfig
import dev.zyriel.voicejournal.audio.WavWriter
import dev.zyriel.voicejournal.search.OnnxEmbeddingEngine
import dev.zyriel.voicejournal.search.VectorSearch
import dev.zyriel.voicejournal.search.WordPieceTokenizer
import dev.zyriel.voicejournal.whisper.Transcriber
import dev.zyriel.voicejournal.data.JournalEntry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Runs the full on-device suite and writes one JSON file. Debug builds only
 * (the UI entry point is gated on BuildConfig.DEBUG).
 *
 * Transcription clips are synthetic tones: whisper's compute scales with
 * audio length, not content, so LATENCY numbers are valid. Accuracy is
 * deliberately not measured here; that needs the committed speech clips.
 */
class BenchmarkSuite(private val context: Context) {

    fun deviceMeta(): Map<String, String> = mapOf(
        "git_sha" to BuildConfig.GIT_SHA,
        "version" to BuildConfig.VERSION_NAME,
        "timestamp" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()),
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "soc_abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
        "cores" to Runtime.getRuntime().availableProcessors().toString(),
        "android" to "API ${Build.VERSION.SDK_INT}",
        "environment" to "device",
    )

    fun runAll(onProgress: (String) -> Unit): File = runBlocking {
        val results = ArrayList<Bench.Result>()

        // ---- Embedding ----
        onProgress("Embedding: engine init")
        var engine: OnnxEmbeddingEngine? = null
        results += Bench.once("embed.init") {
            engine = OnnxEmbeddingEngine(
                modelBytes = context.assets.open("models/bge_small_en_v15_q8.onnx").use { it.readBytes() },
                vocab = context.assets.open("models/bge_vocab.txt").use { WordPieceTokenizer(it) },
            )
        }
        val e = engine!!
        val texts = mapOf(
            "short" to "note to self about the meeting",
            "typical" to "Today I worked on the transcription pipeline and finally got the native build " +
                "running at full speed. The optimization flag was the whole problem. Feeling relieved.",
            "max" to (1..80).joinToString(" ") { "sentence piece number $it of a very long journal entry" },
        )
        for ((label, text) in texts) {
            onProgress("Embedding: $label")
            results += Bench.measure("embed.$label", warmups = 2, runs = 5,
                extra = mapOf("chars" to text.length.toString())) {
                runBlocking { e.embed(text) }
            }
        }
        e.close()

        // ---- Search ----
        val rng = Random(42)
        for (n in intArrayOf(100, 1_000, 5_000)) {
            onProgress("Search: $n vectors")
            val corpus = List(n) { i ->
                JournalEntry(id = i.toLong(), transcript = "e$i", timestampMs = 0, audioPath = "",
                    embedding = FloatArray(384) { rng.nextFloat() - 0.5f })
            }
            val q = FloatArray(384) { rng.nextFloat() - 0.5f }
            results += Bench.measure("search.top20.n$n", warmups = 2, runs = 5,
                extra = mapOf("dims" to "384", "k" to "20")) {
                VectorSearch.topK(q, corpus, 20)
            }
        }

        // ---- Transcription ----
        onProgress("Transcription: model load")
        val transcriber = Transcriber(context)
        val warmClip = syntheticWav(2)
        results += Bench.once("whisper.load_and_first_run") {
            runBlocking { transcriber.transcribe(warmClip) }   // includes asset copy + ctx init
        }
        for (secs in intArrayOf(10, 30, 60)) {
            onProgress("Transcription: ${secs}s clip")
            val clip = syntheticWav(secs)
            val r = Bench.measure("whisper.clip${secs}s", warmups = 1, runs = 3) {
                runBlocking { transcriber.transcribe(clip) }
            }
            val rtf = r.medianMs / (secs * 1000.0)
            results += r.copy(extra = mapOf("clip_s" to "$secs", "realtime_factor" to "%.3f".format(rtf)))
        }
        // ---- VAD comparison ----
        // Same 30s clip, half of it silence, decoded with VAD off then on.
        // CAVEAT: Silero may classify synthetic tones as non-speech and trim
        // more than a real voice would, which inflates the apparent win.
        // These numbers gate nothing; the enable/disable decision waits for
        // the committed real-speech clips (roadmap item 5).
        onProgress("Transcription: VAD comparison")
        val gappyClip = syntheticWav(30, silentEverySecond = true)
        for (vad in booleanArrayOf(false, true)) {
            val label = if (vad) "whisper.clip30s_gaps.vad_on" else "whisper.clip30s_gaps.vad_off"
            results += Bench.measure(label, warmups = 1, runs = 3,
                extra = mapOf("clip_s" to "30", "silence_ratio" to "0.5", "synthetic" to "true")) {
                runBlocking { transcriber.transcribe(gappyClip, useVad = vad) }
            }
        }
        transcriber.release()

        // ---- Write ----
        val sha = BuildConfig.GIT_SHA
        val out = File(context.filesDir, "vj-benchmark-$sha.json")
        out.writeText(Bench.toJson(deviceMeta(), results))
        onProgress("Done: ${out.absolutePath}")
        out
    }

    /**
     * 16 kHz mono tone WAV of [seconds], written to cache. With
     * [silentEverySecond], odd-numbered seconds are zeroed so the clip is
     * 50% silence — the shape the VAD comparison needs.
     */
    private fun syntheticWav(seconds: Int, silentEverySecond: Boolean = false): File {
        val suffix = if (silentEverySecond) "_gaps" else ""
        val f = File(context.cacheDir, "bench_${seconds}s$suffix.wav")
        if (f.exists() && f.length() > 0) return f
        WavWriter(f).use { w ->
            val total = WavWriter.SAMPLE_RATE * seconds
            val chunk = ShortArray(WavWriter.SAMPLE_RATE)
            var written = 0
            var second = 0
            while (written < total) {
                val silent = silentEverySecond && second % 2 == 1
                for (i in chunk.indices) {
                    val t = (written + i).toDouble() / WavWriter.SAMPLE_RATE
                    chunk[i] = if (silent) 0 else (sin(2 * PI * 220 * t) * 8000).toInt().toShort()
                }
                val n = minOf(chunk.size, total - written)
                w.appendSamples(chunk, n)
                written += n
                second++
            }
        }
        return f
    }
}
