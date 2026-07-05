package dev.zyriel.voicejournal.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class OrphanSweepTest {

    private fun tempDir(): File = Files.createTempDirectory("sweep").toFile().apply { deleteOnExit() }

    private fun validWav(dir: File, name: String, samples: Int = 160): File {
        val f = File(dir, name)
        WavWriter(f).use { it.appendSamples(ShortArray(samples) { 50 }) }
        return f
    }

    private fun crashedWav(dir: File, name: String, samples: Int = 160): File {
        val f = validWav(dir, name, samples)
        RandomAccessFile(f, "rw").use { raf ->
            val zero = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
            raf.seek(4); raf.write(zero)
            raf.seek(40); raf.write(zero)
        }
        return f
    }

    @Test
    fun classifiesAllFourKindsInOnePass() {
        val dir = tempDir()
        val referenced = validWav(dir, "entry_referenced.wav")
        val active = crashedWav(dir, "entry_active.wav")          // mid-write right now
        val crashOrphan = crashedWav(dir, "entry_crash.wav")      // death mid-recording
        val insertOrphan = validWav(dir, "entry_noinsert.wav")    // death before DB insert
        val junk = File(dir, "entry_junk.wav").apply { writeBytes(ByteArray(10)) }

        val handed = mutableListOf<File>()
        val report = OrphanSweep.sweep(
            dir = dir,
            referencedPaths = setOf(referenced.absolutePath),
            activePath = { active.absolutePath },
            onRecoverable = { handed += it },
        )

        assertEquals(setOf(crashOrphan, insertOrphan), handed.toSet())
        assertEquals(setOf(crashOrphan, insertOrphan), report.recovered.toSet())
        assertEquals(listOf(junk), report.deleted)
        assertEquals(setOf(referenced, active), report.skipped.toSet())
        assertFalse(junk.exists())
        assertTrue(crashOrphan.exists() && insertOrphan.exists())
    }

    @Test
    fun crashOrphanIsRepairedBeforeBeingHandedOver() {
        val dir = tempDir()
        crashedWav(dir, "entry_a.wav", samples = 400)

        var readable = -1
        OrphanSweep.sweep(dir, emptySet()) { readable = WavReader.readFloatPcm(it).size }

        assertEquals(400, readable)
    }

    @Test
    fun referencedAndActiveFilesAreNeverModified() {
        val dir = tempDir()
        val referenced = crashedWav(dir, "entry_ref.wav")   // crashed-looking but DB-referenced: not ours to touch
        val active = crashedWav(dir, "entry_act.wav")
        val refBytes = referenced.readBytes()
        val actBytes = active.readBytes()

        OrphanSweep.sweep(dir, setOf(referenced.absolutePath), { active.absolutePath }) {
            throw AssertionError("nothing should be recoverable here")
        }

        assertArrayEquals(refBytes, referenced.readBytes())
        assertArrayEquals(actBytes, active.readBytes())
    }

    @Test
    fun nonWavFilesAreIgnored() {
        val dir = tempDir()
        val stray = File(dir, "notes.txt").apply { writeText("not audio") }

        val report = OrphanSweep.sweep(dir, emptySet()) { }

        assertTrue(report.recovered.isEmpty() && report.deleted.isEmpty() && report.skipped.isEmpty())
        assertTrue(stray.exists())
    }

    @Test
    fun missingDirectoryYieldsEmptyReport() {
        val report = OrphanSweep.sweep(File("/nonexistent/nowhere"), emptySet()) {
            throw AssertionError("must not be called")
        }
        assertTrue(report.recovered.isEmpty() && report.deleted.isEmpty() && report.skipped.isEmpty())
    }

    @Test
    fun callbackFailureIsRecordedAndDoesNotAbortTheSweep() {
        // The old contract propagated the first exception, which aborted the
        // loop: one bad orphan shadowed every orphan sorted after it, and the
        // caller's blanket runCatching then made the whole thing invisible.
        // Now a failure is per-file: recorded, file left on disk, sweep goes on.
        val dir = tempDir()
        val bad = validWav(dir, "entry_a_bad.wav")
        val good = validWav(dir, "entry_b_good.wav")

        val handled = mutableListOf<File>()
        val report = OrphanSweep.sweep(dir, emptySet()) { f ->
            if (f == bad) error("transcriber exploded")
            handled += f
        }

        assertEquals(listOf(good), handled)
        assertEquals(listOf(good), report.recovered)
        assertEquals(1, report.failed.size)
        assertEquals(bad, report.failed[0].first)
        assertTrue(report.failed[0].second.message!!.contains("exploded"))
        assertTrue("failed file must stay on disk for the next sweep", bad.exists())
        assertTrue(report.oneLine().contains("1 FAILED"))
    }

    @Test
    fun activePathIsReadPerFileNotSnapshotted() {
        // A recording that becomes active mid-sweep must be skipped even
        // though it wasn't active when the sweep started.
        val dir = tempDir()
        val first = validWav(dir, "entry_1.wav")
        val second = crashedWav(dir, "entry_2.wav")

        var activeNow: String? = null
        val report = OrphanSweep.sweep(dir, emptySet(), { activeNow }) { f ->
            // Recovering the first file "takes time"; the user starts
            // recording into the second during it.
            if (f == first) activeNow = second.absolutePath
        }

        assertEquals(listOf(first), report.recovered)
        assertEquals(listOf(second), report.skipped)
        // Crashed header untouched: sweep never repaired the now-active file.
        assertEquals(0, java.nio.ByteBuffer.wrap(second.readBytes(), 40, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
    }
}
