package dev.zyriel.voicejournal.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Contract C4: import sidecars, canonical paths, per-file reference checks, quarantine. */
class OrphanSweepSidecarTest {

    private fun tempDir(): File = Files.createTempDirectory("sweep").toFile().apply { deleteOnExit() }

    private fun validWav(dir: File, name: String): File {
        val f = File(dir, name)
        WavWriter(f).use { it.appendSamples(ShortArray(160) { 50 }) }
        return f
    }

    private fun importing(wav: File) = File(wav.parentFile, wav.name + OrphanSweep.IMPORT_SIDECAR)
        .apply { writeBytes(ByteArray(0)) }

    private val none: suspend (String) -> Boolean = { false }

    @Test
    fun referencedImportKeepsWavAndDropsOnlyTheSidecar() = runBlocking<Unit> {
        val dir = tempDir()
        val wav = validWav(dir, "import_a.wav")
        val side = importing(wav)
        val bytes = wav.readBytes()

        val report = OrphanSweep.sweep(dir, { it == wav.absolutePath }) {
            throw AssertionError("a referenced import is not an orphan")
        }

        assertFalse(side.exists())
        assertArrayEquals(bytes, wav.readBytes())
        assertEquals(listOf(wav), report.skipped)
        assertTrue(report.deleted.isEmpty())
    }

    @Test
    fun unreferencedImportDeletesBothAndIsNeverTranscribed() = runBlocking<Unit> {
        val dir = tempDir()
        val wav = validWav(dir, "import_b.wav")
        val side = importing(wav)

        val report = OrphanSweep.sweep(dir, none) {
            throw AssertionError("a dead import must not be resurrected as a recording")
        }

        assertFalse(wav.exists())
        assertFalse(side.exists())
        assertEquals(listOf(wav), report.deleted)
        assertTrue(report.recovered.isEmpty() && report.failed.isEmpty())
    }

    @Test
    fun sidecarWithNoWavIsDeleted() = runBlocking<Unit> {
        val dir = tempDir()
        val side = File(dir, "import_c.wav" + OrphanSweep.IMPORT_SIDECAR).apply { writeBytes(ByteArray(0)) }

        val report = OrphanSweep.sweep(dir, none) { throw AssertionError("nothing to recover") }

        assertFalse(side.exists())
        assertTrue(report.deleted.isEmpty())
    }

    @Test
    fun importThatStartsMidSweepIsSkipped() = runBlocking<Unit> {
        // The sidecar is re-checked per WAV, not only in the first pass.
        val dir = tempDir()
        val first = validWav(dir, "entry_1.wav")
        val second = validWav(dir, "entry_2.wav")

        val report = OrphanSweep.sweep(dir, none) { f -> if (f == first) importing(second) }

        assertEquals(listOf(first), report.recovered)
        assertEquals(listOf(second), report.skipped)
        assertTrue(second.exists())
    }

    @Test
    fun referenceIsCheckedPerFileNotSnapshotted() = runBlocking<Unit> {
        // A live recording inserted while the sweep transcribes an earlier
        // orphan must not be inserted a second time.
        val dir = tempDir()
        val first = validWav(dir, "entry_1.wav")
        val second = validWav(dir, "entry_2.wav")
        val db = mutableSetOf<String>()

        val report = OrphanSweep.sweep(dir, { it in db }) { f ->
            db += f.absolutePath
            if (f == first) db += second.absolutePath // the user's own insert lands meanwhile
        }

        assertEquals(listOf(first), report.recovered)
        assertEquals(listOf(second), report.skipped)
    }

    @Test
    fun canonicalPathMatchesADifferentlySpelledReference() = runBlocking<Unit> {
        val real = tempDir()
        File(real, "sub").mkdirs()
        val wav = validWav(real, "entry_x.wav")
        // Same directory reached through "sub/..": absolutePath differs,
        // canonicalPath does not.
        val aliasDir = File(File(real, "sub"), "..")
        val stored = wav.canonicalPath

        val report = OrphanSweep.sweep(aliasDir, { it == stored }) {
            throw AssertionError("referenced under its canonical path")
        }

        assertEquals(1, report.skipped.size)
        assertTrue(wav.exists())
    }

    @Test
    fun canonicalPathMatchesADifferentlySpelledActivePath() = runBlocking<Unit> {
        val real = tempDir()
        File(real, "sub").mkdirs()
        val wav = validWav(real, "entry_y.wav")
        val activeAlias = File(File(File(real, "sub"), ".."), wav.name).absolutePath

        val report = OrphanSweep.sweep(real, none, { activeAlias }) {
            throw AssertionError("active under another spelling")
        }

        assertEquals(listOf(wav), report.skipped)
    }

    @Test
    fun repeatedFailuresQuarantineTheFile() = runBlocking<Unit> {
        val dir = tempDir()
        val wav = validWav(dir, "entry_big.wav")
        val counter = File(dir, wav.name + OrphanSweep.FAILURE_SIDECAR)

        repeat(OrphanSweep.MAX_ATTEMPTS - 1) { attempt ->
            val r = OrphanSweep.sweep(dir, none) { throw IllegalArgumentException("over the cap") }
            assertEquals(1, r.failed.size)
            assertTrue(r.quarantined.isEmpty())
            assertTrue(wav.exists())
            assertEquals((attempt + 1).toString(), counter.readText())
        }

        val last = OrphanSweep.sweep(dir, none) { throw IllegalArgumentException("over the cap") }

        val moved = File(File(dir, OrphanSweep.QUARANTINE_DIR), wav.name)
        assertEquals(listOf(wav), last.quarantined)
        assertFalse(wav.exists())
        assertTrue("audio is kept, not deleted", moved.exists())
        assertFalse(counter.exists())

        // The next sweep ignores the quarantine directory entirely.
        val after = OrphanSweep.sweep(dir, none) { throw AssertionError("quarantined files are not retried") }
        assertTrue(after.recovered.isEmpty() && after.failed.isEmpty() && after.skipped.isEmpty())
    }

    @Test
    fun successAfterAFailureClearsTheCounter() = runBlocking<Unit> {
        val dir = tempDir()
        val wav = validWav(dir, "entry_flaky.wav")
        val counter = File(dir, wav.name + OrphanSweep.FAILURE_SIDECAR)

        OrphanSweep.sweep(dir, none) { error("transient") }
        assertTrue(counter.exists())

        val r = OrphanSweep.sweep(dir, none) { }
        assertEquals(listOf(wav), r.recovered)
        assertFalse(counter.exists())
    }

    @Test
    fun attemptIsCountedBeforeRecoveryRuns() = runBlocking<Unit> {
        // A native crash inside onRecoverable never returns to the sweep, so
        // the count must already be on disk while the callback runs.
        val dir = tempDir()
        val wav = validWav(dir, "entry_crash.wav")
        val counter = File(dir, wav.name + OrphanSweep.FAILURE_SIDECAR)
        var seen: String? = null

        OrphanSweep.sweep(dir, none) { seen = counter.readText() }

        assertEquals("1", seen)
        assertFalse("success clears the counter", counter.exists())
    }

    @Test
    fun exhaustedCounterFromCrashedAttemptsQuarantinesUntried() = runBlocking<Unit> {
        val dir = tempDir()
        val wav = validWav(dir, "entry_sigsegv.wav")
        File(dir, wav.name + OrphanSweep.FAILURE_SIDECAR).writeText(OrphanSweep.MAX_ATTEMPTS.toString())

        val r = OrphanSweep.sweep(dir, none) { throw AssertionError("exhausted files are not retried") }

        assertEquals(listOf(wav), r.quarantined)
        assertTrue(File(File(dir, OrphanSweep.QUARANTINE_DIR), wav.name).exists())
    }

    @Test
    fun strayFailureCounterIsDeleted() = runBlocking<Unit> {
        val dir = tempDir()
        val stray = File(dir, "gone.wav" + OrphanSweep.FAILURE_SIDECAR).apply { writeText("2") }

        OrphanSweep.sweep(dir, none) { }

        assertFalse(stray.exists())
    }

    @Test
    fun importInFlightLeavesItsWavAndSidecarAlone() = runBlocking<Unit> {
        // An import started during the launch sweep is between move and
        // insert: unreferenced, with a sidecar. It must survive the sweep.
        val dir = tempDir()
        val wav = validWav(dir, "import_live.wav")
        val side = importing(wav)

        val report = OrphanSweep.sweep(dir, none, importInFlight = { true }) {
            throw AssertionError("a live import is not an orphan")
        }

        assertTrue(wav.exists() && side.exists())
        assertEquals(listOf(wav), report.skipped)
        assertTrue(report.deleted.isEmpty())

        // Once the import is over, the same pair is a dead import.
        val later = OrphanSweep.sweep(dir, none) { throw AssertionError("dead import") }
        assertEquals(listOf(wav), later.deleted)
        assertFalse(side.exists())
    }
}
