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

    @Test fun fileOverSampleLimitIsRejectedBeforeAllocation() {
        val f = tempWav(ShortArray(1000) { (it % 100).toShort() })
        val ex = assertThrows(IllegalArgumentException::class.java) {
            WavReader.readFloatPcm(f, maxSamples = 999)
        }
        // The message must carry the numbers a log reader needs.
        org.junit.Assert.assertTrue(ex.message!!.contains("1000"))
    }

    @Test fun fileExactlyAtSampleLimitReads() {
        val f = tempWav(ShortArray(1000) { 7 })
        assertEquals(1000, WavReader.readFloatPcm(f, maxSamples = 1000).size)
    }

    @Test fun multiChunkFileRoundTripsExactly() {
        // Larger than one 64 KB read chunk, so the chunked loop's boundary
        // arithmetic is exercised, including a partial final chunk.
        val n = 50_000
        val src = ShortArray(n) { ((it * 31) % 65536 - 32768).toShort() }
        val pcm = WavReader.readFloatPcm(tempWav(src))
        assertEquals(n, pcm.size)
        for (i in 0 until n step 7919) {
            assertEquals(src[i] / 32768f, pcm[i], 0f)
        }
    }
}
