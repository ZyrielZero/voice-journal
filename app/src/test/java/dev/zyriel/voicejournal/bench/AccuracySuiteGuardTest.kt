package dev.zyriel.voicejournal.bench

import dev.zyriel.voicejournal.whisper.Transcriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * The VAD availability guard exists because [Transcriber] silently degrades
 * to no-VAD when the silero asset is missing — correct for normal
 * transcription, silently corrupting for the accuracy suite, whose vad_on
 * rows would score a no-VAD run. Learned the hard way on-device: the model
 * had never been fetched on the build machine and nothing said so.
 */
class AccuracySuiteGuardTest {

    @Test
    fun missingVadAssetFailsLoudly() {
        try {
            AccuracySuite.requireVadAsset { throw FileNotFoundException(it) }
            fail("guard passed with no VAD asset")
        } catch (e: IllegalStateException) {
            val msg = e.message.orEmpty()
            assertTrue("message must name the asset: $msg", Transcriber.VAD_ASSET in msg)
            assertTrue("message must say how to fix it: $msg", "fetch-models" in msg)
        }
    }

    @Test
    fun presentVadAssetPasses() {
        var opened: String? = null
        AccuracySuite.requireVadAsset { opened = it }
        assertEquals(Transcriber.VAD_ASSET, opened)
    }

    /**
     * The fetched silero file itself, same discipline as the committed-clips
     * test: "file exists" is not "file is valid". A truncated download or an
     * HTML error page saved by curl would pass an existence check and brick
     * VAD at runtime. CI fetches models before the test task, so this runs
     * everywhere.
     */
    @Test
    fun fetchedVadModelIsValid() {
        val f = File("src/main/assets/${Transcriber.VAD_ASSET}")
        assertTrue("VAD model missing at ${f.absolutePath}; run scripts/fetch-models", f.exists())
        assertTrue("VAD model implausibly small (${f.length()} bytes)", f.length() > 500_000)
        val magic = f.inputStream().use { it.readNBytes(4) }
        assertEquals("not a ggml file", "lmgg", magic.toString(Charsets.US_ASCII))
    }
}
