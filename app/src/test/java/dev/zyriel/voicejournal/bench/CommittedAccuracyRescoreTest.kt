package dev.zyriel.voicejournal.bench

import dev.zyriel.voicejournal.TestPaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Re-scores the committed on-device hypotheses (docs/benchmarks) against the
 * committed manifest with the current [WordErrorRate]. This needs no device:
 * the hypotheses are raw whisper output already in the JSON. It pins the
 * figures the README quotes, so a normalizer change that moves them fails
 * here instead of leaving the README silently wrong.
 */
class CommittedAccuracyRescoreTest {

    private fun errorsByMode(): Map<String, Pair<Int, Int>> {
        val manifest = AccuracyManifest.parse(TestPaths.debugAsset("accuracy/manifest.json").readText())
        val refs = manifest.clips.associate { it.file to it.reference }
        val json = JSONObject(TestPaths.repoFile("docs/benchmarks/vj-accuracy-ce5f560.json").readText())
        val results = json.getJSONArray("results")
        val totals = HashMap<String, IntArray>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val extra = r.getJSONObject("extra")
            if (!extra.has("hypothesis")) continue
            val name = r.getString("name").removePrefix("acc.")
            val file = name.substringBeforeLast('.')
            val mode = name.substringAfterLast('.')
            val a = WordErrorRate.align(refs.getValue(file), extra.getString("hypothesis"))
            totals.getOrPut(mode) { IntArray(2) }.let { it[0] += a.errors; it[1] += a.referenceWords }
        }
        return totals.mapValues { (_, t) -> t[0] to t[1] }
    }

    @Test
    fun committedHypothesesRescoreToReadmeFigures() {
        val t = errorsByMode()
        // Committed under the old normalizer: 14 and 10 errors in 222 words
        // (6.31% and 4.50%). The UK/US map removes one "grey"/GRAY error per mode.
        assertEquals(13 to 222, t["vad_off"])
        assertEquals(9 to 222, t["vad_on"])
    }
}
