package dev.zyriel.voicejournal.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class WavReaderTest {

    private fun tempWav(samples: ShortArray): File {
        val f = File.createTempFile("reader", ".wav").apply { deleteOnExit() }
        WavWriter(f).use { it.appendSamples(samples) }
        return f
    }

    @Test fun roundTripThroughWriterNormalizesToFloat() {
        val f = tempWav(shortArrayOf(0, 16384, -16384, 32767, -32768))
        val pcm = WavReader.readFloatPcm(f)
        assertEquals(5, pcm.size)
        assertEquals(0f, pcm[0], 0f)
        assertEquals(0.5f, pcm[1], 1e-4f)
        assertEquals(-0.5f, pcm[2], 1e-4f)
        assertEquals(1f, pcm[3], 1e-3f)
        assertEquals(-1f, pcm[4], 0f)
    }

    @Test fun emptyWavGivesEmptyArray() {
        assertEquals(0, WavReader.readFloatPcm(tempWav(ShortArray(0))).size)
    }

    @Test fun garbageFileThrows() {
        val f = File.createTempFile("garbage", ".wav").apply {
            deleteOnExit(); writeBytes(ByteArray(100) { 42 })
        }
        assertThrows(IllegalArgumentException::class.java) { WavReader.readFloatPcm(f) }
    }

    @Test fun truncatedFileThrows() {
        val f = File.createTempFile("tiny", ".wav").apply { deleteOnExit(); writeBytes(ByteArray(10)) }
        assertThrows(IllegalArgumentException::class.java) { WavReader.readFloatPcm(f) }
    }
}
