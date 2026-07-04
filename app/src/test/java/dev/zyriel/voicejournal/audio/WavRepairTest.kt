package dev.zyriel.voicejournal.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavRepairTest {

    private fun tempFile(): File = File.createTempFile("wavrepair", ".wav").apply { deleteOnExit() }

    /** Writes a valid file, then zeroes both size fields to reproduce the
     *  exact on-disk state WavWriter leaves when the process dies before close(). */
    private fun crashedFile(sampleCount: Int): File {
        val f = tempFile()
        val samples = ShortArray(sampleCount) { ((it * 37) % 2048).toShort() }
        WavWriter(f).use { it.appendSamples(samples) }
        RandomAccessFile(f, "rw").use { raf ->
            val zero = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
            raf.seek(4); raf.write(zero)
            raf.seek(40); raf.write(zero)
        }
        return f
    }

    @Test
    fun crashedFileIsRepairedAndBecomesReadable() {
        val f = crashedFile(800)  // 50 ms

        // Before repair, WavReader trusts the zeroed header and sees nothing.
        assertEquals(0, WavReader.readFloatPcm(f).size)

        val outcome = WavRepair.inspectAndRepair(f)
        assertEquals(WavRepair.Outcome.Repaired(800L), outcome)
        assertEquals(800, WavReader.readFloatPcm(f).size)
    }

    @Test
    fun repairedAudioMatchesWhatWasRecorded() {
        val f = tempFile()
        val samples = ShortArray(320) { ((it * 91) % 4096).toShort() }
        WavWriter(f).use { it.appendSamples(samples) }
        val reference = WavReader.readFloatPcm(f)

        RandomAccessFile(f, "rw").use { raf ->
            val zero = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
            raf.seek(4); raf.write(zero)
            raf.seek(40); raf.write(zero)
        }
        WavRepair.inspectAndRepair(f)

        assertArrayEquals(reference, WavReader.readFloatPcm(f), 0f)
    }

    @Test
    fun cleanlyClosedFileIsLeftByteForByteUntouched() {
        val f = tempFile()
        WavWriter(f).use { it.appendSamples(ShortArray(160) { 100 }) }
        val before = f.readBytes()

        assertEquals(WavRepair.Outcome.AlreadyConsistent, WavRepair.inspectAndRepair(f))
        assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun oddTrailingByteIsTruncatedToWholeSamples() {
        val f = crashedFile(500)
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(0x7F)  // half a sample, written at the moment of death
        }

        val outcome = WavRepair.inspectAndRepair(f)
        assertEquals(WavRepair.Outcome.Repaired(500L), outcome)
        assertEquals(0L, (f.length() - WavWriter.HEADER_SIZE) % 2)
        assertEquals(500, WavReader.readFloatPcm(f).size)
    }

    @Test
    fun headerOnlyFileIsUnrecoverable() {
        val f = tempFile()
        WavWriter(f).use { }  // header, zero samples, cleanly closed

        val outcome = WavRepair.inspectAndRepair(f)
        assertTrue(outcome is WavRepair.Outcome.Unrecoverable)
        assertEquals("no audio data", (outcome as WavRepair.Outcome.Unrecoverable).reason)
    }

    @Test
    fun tooSmallFileIsUnrecoverable() {
        val f = tempFile()
        f.writeBytes(ByteArray(20))

        assertTrue(WavRepair.inspectAndRepair(f) is WavRepair.Outcome.Unrecoverable)
    }

    @Test
    fun foreignFileIsUnrecoverableAndUnmodified() {
        val f = tempFile()
        val junk = ByteArray(4096) { (it % 251).toByte() }
        f.writeBytes(junk)

        assertTrue(WavRepair.inspectAndRepair(f) is WavRepair.Outcome.Unrecoverable)
        assertArrayEquals(junk, f.readBytes())
    }

    @Test
    fun repairIsIdempotent() {
        val f = crashedFile(256)
        assertEquals(WavRepair.Outcome.Repaired(256L), WavRepair.inspectAndRepair(f))
        assertEquals(WavRepair.Outcome.AlreadyConsistent, WavRepair.inspectAndRepair(f))
    }
}
