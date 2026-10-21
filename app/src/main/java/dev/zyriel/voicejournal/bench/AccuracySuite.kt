package dev.zyriel.voicejournal.bench

import android.content.Context
import dev.zyriel.voicejournal.whisper.Transcriber
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

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
 * Timing: one untimed warm-up per VAD state, then three timed runs per row
 * (median reported), so no row includes model or VAD init. Each row records
 * use_vad explicitly. The overall rows print the sample size (clips,
 * utterances, reference words) and a 95% Wilson interval beside the WER,
 * because three clips cannot separate models a point or two apart. A
 * final pair of rows times real speech with pauses (see [GapsClip]).
 *
 * Output mirrors the latency suite: one JSON in filesDir, name
 * vj-accuracy-<sha>.json, pulled with the same adb exec-out run-as
 * incantation and committed under docs/benchmarks/.
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
        val wavs = LinkedHashMap<AccuracyManifest.Clip, File>()
        try {
            // Assets are compressed; the transcriber needs real files.
            for (clip in manifest.clips) {
                val wav = File(context.cacheDir, "acc_${clip.file}")
                context.assets.open("accuracy/${clip.file}").use { input ->
                    wav.outputStream().use { input.copyTo(it) }
                }
                wavs[clip] = wav
            }

            // Warm up once per VAD state before anything is timed, so no row
            // carries whisper_init (VAD off) or the VAD model copy and init
            // (VAD on). Previously the first clip of each mode absorbed both.
            val warm = wavs.values.first()
            for (vad in booleanArrayOf(false, true)) {
                onProgress("Warm-up ${vadLabel(vad)}")
                transcriber.transcribe(warm, useVad = vad)
            }

            // Aggregate error counts across clips, per VAD mode, so the
            // headline number is corpus-level WER (total errors over total
            // reference words), not an average of per-clip rates that would
            // overweight the short clip. Failed clips are reported, not scored.
            val totals = HashMap<Boolean, IntArray>() // vad -> [S, D, I, refWords, clips, failed]

            for ((clip, wav) in wavs) {
                for (vad in booleanArrayOf(false, true)) {
                    val mode = vadLabel(vad)
                    onProgress("${clip.file} $mode")
                    val row = scoredRow("acc.${clip.file}.$mode", clip.reference, clip.durationS, wav, vad, transcriber)
                    results += row.result
                    totals.getOrPut(vad) { IntArray(6) }.let {
                        val a = row.alignment
                        if (a == null) {
                            it[5]++
                        } else {
                            it[0] += a.substitutions; it[1] += a.deletions
                            it[2] += a.insertions; it[3] += a.referenceWords; it[4]++
                        }
                    }
                }
            }

            for ((vad, t) in totals.toSortedMap()) {
                val mode = vadLabel(vad)
                val errors = t[0] + t[1] + t[2]
                val extra = linkedMapOf(
                    "use_vad" to vad.toString(),
                    "clips_scored" to t[4].toString(),
                    "clips_failed" to t[5].toString(),
                    "utterances" to manifest.clips.sumOf { it.librispeechIds.size }.toString(),
                    "reference_words" to t[3].toString(),
                    "substitutions" to t[0].toString(),
                    "deletions" to t[1].toString(),
                    "insertions" to t[2].toString(),
                    "corpus" to "LibriSpeech test-clean subset (see manifest)",
                    "normalizer" to "plain: lowercase, punctuation, U+2019, small UK/US map",
                )
                if (t[3] > 0) {
                    val (lo, hi) = WordErrorRate.wilsonInterval95(errors, t[3])
                    extra["wer"] = fmt4(errors.toDouble() / t[3])
                    extra["wer_ci95_low"] = fmt4(lo)
                    extra["wer_ci95_high"] = fmt4(hi)
                }
                results += Bench.Result(
                    name = "acc.overall.$mode", runsMs = listOf(0), medianMs = 0, extra = extra,
                )
            }

            // ---- VAD latency on real speech with pauses ----
            // Half speech, half room tone, built from the committed clips so
            // silero sees real voice. Replaces the synthetic-tone gaps rows.
            val sources = wavs.values.take(2)
            val gappy = File(context.cacheDir, "acc_gaps.wav")
            val samples = GapsClip.build(sources, gappy)
            val gapRef = wavs.keys.take(2).joinToString(" ") { it.reference }
            val gapSecs = samples / 16_000.0
            for (vad in booleanArrayOf(false, true)) {
                val mode = vadLabel(vad)
                onProgress("Speech with pauses $mode")
                val row = scoredRow("acc.speech_gaps.$mode", gapRef, gapSecs, gappy, vad, transcriber,
                    extraTags = mapOf("silence_ratio" to GapsClip.SILENCE_RATIO.toString()))
                results += row.result
            }
            gappy.delete()
        } finally {
            transcriber.release()
            wavs.values.forEach { it.delete() }
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

    private class Row(val result: Bench.Result, val alignment: WordErrorRate.Alignment?)

    /**
     * Times [wav] three times with an explicit VAD state and scores the last
     * hypothesis. A null transcript (native failure, contract C1) makes the
     * row a failed row with no WER, never an empty hypothesis scored as all
     * deletions.
     */
    private fun scoredRow(
        name: String,
        reference: String,
        clipSeconds: Double,
        wav: File,
        vad: Boolean,
        transcriber: Transcriber,
        extraTags: Map<String, String> = emptyMap(),
    ): Row {
        var hypothesis: String? = null
        var failed = false
        val timing = Bench.measure(name, warmups = 0, runs = 3) {
            val h: String? = runBlocking { transcriber.transcribe(wav, useVad = vad) }
            if (h == null) failed = true
            hypothesis = h
        }
        val base = linkedMapOf(
            "clip_s" to String.format(Locale.US, "%.2f", clipSeconds),
            "use_vad" to vad.toString(),
        )
        base.putAll(extraTags)
        val text = hypothesis
        if (failed || text == null) {
            base["failed"] = "true"
            return Row(timing.copy(extra = base), null)
        }
        val a = WordErrorRate.align(reference, text)
        base["realtime_factor"] = String.format(Locale.US, "%.3f", timing.medianMs / (clipSeconds * 1000.0))
        base["wer"] = fmt4(a.wer)
        base["substitutions"] = a.substitutions.toString()
        base["deletions"] = a.deletions.toString()
        base["insertions"] = a.insertions.toString()
        base["reference_words"] = a.referenceWords.toString()
        base["hypothesis"] = text
        return Row(timing.copy(extra = base), a)
    }

    private fun vadLabel(vad: Boolean) = if (vad) "vad_on" else "vad_off"

    private fun fmt4(x: Double) = String.format(Locale.US, "%.4f", x)
}
