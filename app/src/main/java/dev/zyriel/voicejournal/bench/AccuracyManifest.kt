package dev.zyriel.voicejournal.bench

import org.json.JSONObject

/**
 * Parses the committed accuracy manifest (debug assets, accuracy/manifest.json).
 * Pure JVM for the same reason [Bench] and [WordErrorRate] are: the input
 * handling of the accuracy suite is under test, so a malformed manifest fails
 * loudly in CI rather than silently producing a benchmark that scored the
 * wrong reference text.
 *
 * org.json is an Android framework class at runtime; the JVM tests use the
 * standalone artifact already declared for JournalArchiveTest.
 */
object AccuracyManifest {

    data class Clip(
        /** WAV filename relative to the accuracy asset dir. */
        val file: String,
        /** Duration in seconds, informational (from the corpus, not re-measured). */
        val durationS: Double,
        /** LibriSpeech utterance IDs, provenance for the committed audio. */
        val librispeechIds: List<String>,
        /** The reference transcript the hypothesis is scored against. */
        val reference: String,
    )

    data class Manifest(
        val license: String,
        val attribution: String,
        val clips: List<Clip>,
    )

    /**
     * Throws on anything structurally wrong: missing fields, empty clip list,
     * or a clip whose reference normalizes to zero words (which would make
     * WER a division by zero much later, far from the actual mistake).
     */
    fun parse(json: String): Manifest {
        val root = JSONObject(json)
        val clipsJson = root.getJSONArray("clips")
        require(clipsJson.length() > 0) { "manifest contains no clips" }
        val clips = ArrayList<Clip>(clipsJson.length())
        for (i in 0 until clipsJson.length()) {
            val c = clipsJson.getJSONObject(i)
            val reference = c.getString("reference")
            require(WordErrorRate.normalize(reference).isNotEmpty()) {
                "clip ${c.getString("file")} has an empty reference after normalization"
            }
            val ids = c.getJSONArray("librispeech_ids")
            clips += Clip(
                file = c.getString("file"),
                durationS = c.getDouble("duration_s"),
                librispeechIds = List(ids.length()) { ids.getString(it) },
                reference = reference,
            )
        }
        return Manifest(
            license = root.getString("license"),
            attribution = root.getString("attribution"),
            clips = clips,
        )
    }
}
