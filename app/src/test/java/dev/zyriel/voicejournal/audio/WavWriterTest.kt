package dev.zyriel.voicejournal.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class WavWriterTest {

    private fun tempFile(): File = File.createTempFile("wavtest", ".wav").apply { deleteOnExit() }

    private fun header(f: File): ByteBuffer =
        ByteBuffer.wrap(f.readBytes(), 0, WavWriter.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    private fun ascii(b: ByteBuffer, at: Int, len: Int): String {
        val out = ByteArray(len)
        for (i in 0 until len) out[i] = b.get(at + i)
        return String(out, Charsets.US_ASCII)
    }

    @Test
    fun headerFieldsAreCanonicalPcm16kMono() {
        val f = tempFile()
        WavWriter(f).use { it.appendSamples(ShortArray(160)) }  // 10 ms of silence
        val h = header(f)

        assertEquals("RIFF", ascii(h, 0, 4))
        assertEquals("WAVE", ascii(h, 8, 4))
        assertEquals("fmt ", ascii(h, 12, 4))
        assertEquals(16, h.getInt(16))                    // PCM fmt chunk size
        assertEquals(1, h.getShort(20).toInt())           // PCM format tag
        assertEquals(1, h.getShort(22).toInt())           // mono
        assertEquals(16_000, h.getInt(24))                // sample rate
        assertEquals(32_000, h.getInt(28))                // byte rate
        assertEquals(2, h.getShort(32).toInt())           // block align
        assertEquals(16, h.getShort(34).toInt())          // bits per sample
        assertEquals("data", ascii(h, 36, 4))
    }

    @Test
    fun chunkSizesMatchWrittenData() {
        val f = tempFile()
        WavWriter(f).use {
            it.appendSamples(ShortArray(1000))
            it.appendSamples(ShortArray(600))
        }
        val h = header(f)
        val dataBytes = 1600 * 2
        assertEquals(dataBytes, h.getInt(40))             // data chunk size
        assertEquals(36 + dataBytes, h.getInt(4))         // RIFF chunk size
        assertEquals((WavWriter.HEADER_SIZE + dataBytes).toLong(), f.length())
    }

    @Test
    fun samplesRoundTripLittleEndian() {
        val f = tempFile()
        val tone = ShortArray(320) { (sin(2 * PI * 440 * it / 16_000) * 12_000).toInt().toShort() }
        WavWriter(f).use { it.appendSamples(tone) }

        val bytes = f.readBytes()
        val body = ByteBuffer.wrap(bytes, WavWriter.HEADER_SIZE, bytes.size - WavWriter.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        val readBack = ShortArray(tone.size) { body.short }
        assertEquals(tone.toList(), readBack.toList())
    }

    @Test
    fun partialCountWritesOnlyRequestedSamples() {
        val f = tempFile()
        WavWriter(f).use { w ->
            val chunk = ShortArray(4096) { it.toShort() }
            w.appendSamples(chunk, count = 100)           // AudioRecord read() returns fewer than buffer size
            assertEquals(100L, w.sampleCount())
        }
        assertEquals(100 * 2, header(f).getInt(40))
    }

    @Test
    fun durationIsDerivedFromSampleRate() {
        val f = tempFile()
        WavWriter(f).use { w ->
            w.appendSamples(ShortArray(16_000))           // exactly 1 second
            assertEquals(1000L, w.durationMs())
            w.appendSamples(ShortArray(8_000))            // +0.5 s
            assertEquals(1500L, w.durationMs())
        }
    }

    @Test
    fun emptyRecordingProducesValidZeroDataFile() {
        val f = tempFile()
        WavWriter(f).use { }
        val h = header(f)
        assertEquals(0, h.getInt(40))
        assertEquals(36, h.getInt(4))
        assertEquals(WavWriter.HEADER_SIZE.toLong(), f.length())
    }

    @Test
    fun closeIsIdempotent() {
        val f = tempFile()
        val w = WavWriter(f)
        w.appendSamples(ShortArray(10))
        w.close()
        w.close()
        assertEquals(10 * 2, header(f).getInt(40))
    }

    @Test
    fun appendAfterCloseThrows() {
        val w = WavWriter(tempFile())
        w.close()
        assertThrows(IllegalStateException::class.java) { w.appendSamples(ShortArray(1)) }
    }

    @Test
    fun invalidCountThrows() {
        WavWriter(tempFile()).use { w ->
            assertThrows(IllegalArgumentException::class.java) { w.appendSamples(ShortArray(4), count = 5) }
        }
    }
}
