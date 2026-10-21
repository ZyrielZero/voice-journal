package dev.zyriel.voicejournal.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Header fields WavRepair must read correctly on files from other tools. */
class WavRepairHeaderTest {

    private fun wav(samples: Int): File {
        val f = File.createTempFile("wavhdr", ".wav").apply { deleteOnExit() }
        WavWriter(f).use { it.appendSamples(ShortArray(samples) { 7 }) }
        return f
    }

    private fun poke(f: File, offset: Long, bytes: ByteArray) =
        RandomAccessFile(f, "rw").use { it.seek(offset); it.write(bytes) }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    @Test
    fun streamingMarkerSizesArePatched() {
        // 0xFFFFFFFF in both size fields: the "length unknown" marker that
        // streaming writers emit. Must be treated as wrong, not as -1.
        val f = wav(320)
        poke(f, 4, le32(-1))
        poke(f, 40, le32(-1))

        assertEquals(WavRepair.Outcome.Repaired(320L), WavRepair.inspectAndRepair(f))
        assertEquals(320, WavReader.readFloatPcm(f).size)
        val h = ByteBuffer.wrap(f.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 640, h.getInt(4))
        assertEquals(640, h.getInt(40))
    }

    @Test
    fun wrongByteRateIsUnrecoverableAndUntouched() {
        val f = wav(160)
        poke(f, 28, le32(44_100 * 2))
        val before = f.readBytes()

        val outcome = WavRepair.inspectAndRepair(f)

        assertTrue(outcome is WavRepair.Outcome.Unrecoverable)
        assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun wrongBlockAlignIsUnrecoverableAndUntouched() {
        val f = wav(160)
        poke(f, 32, le16(4))
        val before = f.readBytes()

        val outcome = WavRepair.inspectAndRepair(f)

        assertTrue(outcome is WavRepair.Outcome.Unrecoverable)
        assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun ourOwnFileIsStillConsistent() {
        assertEquals(WavRepair.Outcome.AlreadyConsistent, WavRepair.inspectAndRepair(wav(160)))
    }
}
