package dev.zyriel.voicejournal.bench

import android.content.Context
import dev.zyriel.voicejournal.whisper.Transcriber
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Transcription accuracy suite. Debug builds only, same gate as
 * [BenchmarkSuite]; the committed speech clips live in the DEBUG asset set
 * so they never ship in a release APK.
 *
 * Runs each committed clip through the real [Transcriber] twice — VAD off
 * and VAD on — and scores both against the manifest reference with
 * [WordErrorRate]. The off/on pairing is deliberate: the VAD enable
 * decision (roadmap item 4's open question) needs the accuracy cost of
 * trimming measured on real speech, not inferred from synthetic tones. A
 * deletion-heavy VAD row is the trim eating words; that is the number that
 * decides whether VAD ships enabled.
 *
 * Output mirrors the latency suite: one JSON in filesDir, name
 * vj-accuracy-<sha>.json, pulled with the same adb exec-out run-as
 * incantation and committed next to the latency file.
 */
class AccuracySuite(private val context: Context) {

    companion object {
        /**
         * Fails loudly when the VAD model asset can't be opened. [Transcriber]
         * deliberately degrades to no-VAD when the asset is missing — right
         * for normal transcription, poison here: every vad_on row would
         * silently score a no-VAD run and the off/on comparison this suite
         * exists to produce would look meaningful while measuring nothing.
         */
        fun requireVadAsset(open: (String) -> Unit) {
            try {
                open(Transcriber.VAD_ASSET)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "VAD model asset '${Transcriber.VAD_ASSET}' is missing; " +
                        "vad_on rows would silently run without VAD. " +
                        "Run scripts/fetch-models and rebuild.",
                    e,
                )
            }
        }
    }

    fun runAll(onProgress: (String) -> Unit): File = runBlocking {
        requireVadAsset { context.assets.open(it).close() }
        val manifest = AccuracyManifest.parse(
            context.assets.open("accuracy/manifest.json").use { it.readBytes().decodeToString() }
        )
        val results = ArrayList<Bench.Result>()
        val transcriber = Transcriber(context)
        try {
            // Aggregate error counts across clips, per VAD mode, so the
            // headline number is corpus-level WER (total errors over total
            // reference words), not an average of per-clip rates that would
            // overweight the short clip.
            val totals = HashMap<Boolean, IntArray>() // vad -> [S, D, I, refWords]

            for (clip in manifest.clips) {
                // Assets are compressed; the transcriber needs a real file.
                val wav = File(context.cacheDir, "acc_${clip.file}")
                context.assets.open("accuracy/${clip.file}").use { input ->
                    wav.outputStream().use { input.copyTo(it) }
                }
                for (vad in booleanArrayOf(false, true)) {
                    val mode = if (vad) "vad_on" else "vad_off"
                    onProgress("${clip.file} $mode")
                    var hypothesis = ""
                    val timing = Bench.once("acc.${clip.file}.$mode") {
                        hypothesis = runBlocking { transcriber.transcribe(wav, useVad = vad) }
                    }
                    val a = WordErrorRate.align(clip.reference, hypothesis)
                    totals.getOrPut(vad) { IntArray(4) }.let {
                        it[0] += a.substitutions; it[1] += a.deletions
                        it[2] += a.insertions; it[3] += a.referenceWords
                    }
                    results += timing.copy(extra = mapOf(
                        "clip_s" to clip.durationS.toString(),
                        "wer" to "%.4f".format(a.wer),
                        "substitutions" to a.substitutions.toString(),
                        "deletions" to a.deletions.toString(),
                        "insertions" to a.insertions.toString(),
                        "reference_words" to a.referenceWords.toString(),
                        "hypothesis" to hypothesis,
                    ))
                }
                wav.delete()
            }

            for ((vad, t) in totals.toSortedMap()) {
                val mode = if (vad) "vad_on" else "vad_off"
                val wer = (t[0] + t[1] + t[2]).toDouble() / t[3]
                results += Bench.Result(
                    name = "acc.overall.$mode", runsMs = listOf(0), medianMs = 0,
                    extra = mapOf(
                        "wer" to "%.4f".format(wer),
                        "substitutions" to t[0].toString(),
                        "deletions" to t[1].toString(),
                        "insertions" to t[2].toString(),
                        "reference_words" to t[3].toString(),
                        "corpus" to "LibriSpeech test-clean subset (see manifest)",
                    ),
                )
            }
        } finally {
            transcriber.release()
        }

        val meta = BenchmarkSuite(context).deviceMeta() + mapOf(
            "model" to "ggml-base.en-q5_1",
            "suite" to "accuracy",
        )
        val out = File(context.filesDir, "vj-accuracy-${meta.getValue("git_sha")}.json")
        out.writeText(Bench.toJson(meta, results))
        onProgress("Done: ${out.absolutePath}")
        out
    }
}
