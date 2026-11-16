package dev.zyriel.voicejournal.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Getting audio back out of quarantine: retry and delete. */
class QuarantineTest {

    private val none: suspend (String) -> Boolean = { false }

    private fun tempDir(): File = Files.createTempDirectory("quarantine").toFile().apply { deleteOnExit() }

    private fun validWav(dir: File, name: String): File {
        val f = File(dir, name)
        WavWriter(f).use { it.appendSamples(ShortArray(160) { 50 }) }
        return f
    }

    /** Fails recovery of every orphan until each one is quarantined. */
    private fun quarantineAll(dir: File) = runBlocking {
        repeat(OrphanSweep.MAX_ATTEMPTS) {
            OrphanSweep.sweep(dir, none) { throw IllegalStateException("cannot transcribe") }
        }
    }

    @Test
    fun releasedFilesAreRetriedWithACleanCount() = runBlocking<Unit> {
        val dir = tempDir()
        validWav(dir, "entry_a.wav")
        validWav(dir, "entry_b.wav")
        quarantineAll(dir)
        assertEquals(2, OrphanSweep.quarantinedCount(dir))
        assertFalse(File(dir, "entry_a.wav").exists())

        assertEquals(2, OrphanSweep.releaseQuarantine(dir))
        assertEquals(0, OrphanSweep.quarantinedCount(dir))
        assertFalse(File(dir, "entry_a.wav" + OrphanSweep.FAILURE_SIDECAR).exists())

        val handed = mutableListOf<String>()
        val report = OrphanSweep.sweep(dir, none) { handed += it.name }
        assertEquals(listOf("entry_a.wav", "entry_b.wav"), handed)
        assertEquals(2, report.recovered.size)
        assertTrue(report.quarantined.isEmpty())
    }

    @Test
    fun aReleasedFileThatStillFailsGetsAllItsAttemptsAgain() = runBlocking<Unit> {
        val dir = tempDir()
        validWav(dir, "entry_a.wav")
        quarantineAll(dir)
        OrphanSweep.releaseQuarantine(dir)

        // One more failure must not send it straight back.
        val r = OrphanSweep.sweep(dir, none) { throw IllegalStateException("still broken") }
        assertEquals(1, r.failed.size)
        assertTrue(r.quarantined.isEmpty())
        assertTrue(File(dir, "entry_a.wav").exists())
    }

    @Test
    fun releaseNeverOverwritesAFileThatTookTheName() {
        val dir = tempDir()
        validWav(dir, "entry_a.wav")
        quarantineAll(dir)
        val newer = validWav(dir, "entry_a.wav") // a new recording reused the name
        val before = newer.readBytes()

        assertEquals(1, OrphanSweep.releaseQuarantine(dir))
        assertTrue(before.contentEquals(newer.readBytes()))
        assertTrue(File(dir, "1_entry_a.wav").exists())
    }

    @Test
    fun deleteRemovesOnlyQuarantinedAudio() {
        val dir = tempDir()
        validWav(dir, "entry_a.wav")
        quarantineAll(dir)
        val live = validWav(dir, "entry_live.wav")

        assertEquals(1, OrphanSweep.deleteQuarantine(dir))
        assertEquals(0, OrphanSweep.quarantinedCount(dir))
        assertTrue(live.exists())
    }

    @Test
    fun nothingQuarantinedIsANoOp() {
        val dir = tempDir()
        assertEquals(0, OrphanSweep.releaseQuarantine(dir))
        assertEquals(0, OrphanSweep.deleteQuarantine(dir))
        assertTrue(OrphanSweep.quarantinedFiles(dir).isEmpty())
    }
}
