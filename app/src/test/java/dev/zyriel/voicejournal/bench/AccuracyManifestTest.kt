package dev.zyriel.voicejournal.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccuracyManifestTest {

    private fun minimal(reference: String = "some spoken words") = """
        {
          "license": "L",
          "attribution": "A",
          "clips": [
            {"file": "clip10s.wav", "duration_s": 10.4,
             "librispeech_ids": ["1089-134686-0000"],
             "reference": "$reference"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesMinimalManifest() {
        val m = AccuracyManifest.parse(minimal())
        assertEquals(1, m.clips.size)
        assertEquals("clip10s.wav", m.clips[0].file)
        assertEquals(10.4, m.clips[0].durationS, 1e-9)
        assertEquals(listOf("1089-134686-0000"), m.clips[0].librispeechIds)
    }

    @Test
    fun emptyClipListThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            AccuracyManifest.parse("""{"license":"L","attribution":"A","clips":[]}""")
        }
    }

    @Test
    fun referenceThatNormalizesToNothingThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            AccuracyManifest.parse(minimal(reference = "... !!! ..."))
        }
    }

    @Test
    fun missingFieldThrows() {
        // org.json throws JSONException for a missing key; any throw is a loud
        // failure, which is the contract. JSONException is not an IAE, so
        // assert on the common supertype.
        assertThrows(Exception::class.java) {
            AccuracyManifest.parse("""{"clips":[{"file":"x.wav"}]}""")
        }
    }

    /**
     * The committed manifest itself must parse and its references must all
     * survive normalization. Runs against the real debug asset so a bad
     * edit to manifest.json fails in CI, not on a device mid-benchmark.
     */
    @Test
    fun committedManifestIsValid() {
        val f = File("src/debug/assets/accuracy/manifest.json")
        assertTrue("committed manifest missing at ${f.absolutePath}", f.exists())
        val m = AccuracyManifest.parse(f.readText())
        assertEquals(3, m.clips.size)
        for (clip in m.clips) {
            assertTrue(
                "reference for ${clip.file} too short to be a real clip",
                WordErrorRate.normalize(clip.reference).size >= 3,
            )
            assertTrue(
                "committed WAV missing for ${clip.file}",
                File("src/debug/assets/accuracy/${clip.file}").exists(),
            )
        }
    }
}
