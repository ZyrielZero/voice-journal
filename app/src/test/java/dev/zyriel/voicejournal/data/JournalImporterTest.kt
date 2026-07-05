package dev.zyriel.voicejournal.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JournalImporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var staging: File
    private lateinit var recordings: File

    private fun importer(): JournalImporter {
        staging = File(tmp.root, "staging")
        recordings = File(tmp.root, "recordings").apply { mkdirs() }
        return JournalImporter(staging, recordings)
    }

    private fun archiveOf(vararg entries: Triple<String, Long, ByteArray?>): ByteArray {
        val src = tmp.newFolder()
        val list = entries.mapIndexed { i, (text, ts, audio) ->
            val path = audio?.let {
                File(src, "entry_$i.wav").apply { writeBytes(it) }.absolutePath
            } ?: File(src, "missing_$i.wav").absolutePath
            JournalEntry(id = i + 1L, transcript = text, timestampMs = ts, audioPath = path)
        }
        return ByteArrayOutputStream().also { out ->
            JournalArchive.export(list, { File(it.audioPath).takeIf(File::isFile) }, out, "t")
        }.toByteArray()
    }

    private fun wavsIn(dir: File) = dir.listFiles()?.filter { it.extension == "wav" } ?: emptyList()

    // The H1 sequence: a rejected archive must leave recordings/ exactly as
    // OrphanSweep expects — nothing unreferenced — and staging must be gone.
    @Test
    fun rejectedArchiveLeavesRecordingsUntouched() {
        val imp = importer()
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                // Audio member first (it streams to disk before validation)...
                z.putNextEntry(ZipEntry("audio/0_x.wav")); z.write(ByteArray(500) { 3 }); z.closeEntry()
                // ...then a manifest that fails version validation.
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("""{"formatVersion": 999, "entries": []}""".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()

        try {
            imp.import(ByteArrayInputStream(zipBytes), emptySet()) { fail("nothing may insert") }
            fail("expected ArchiveFormatException")
        } catch (e: JournalArchive.ArchiveFormatException) {
            // expected
        }

        assertTrue("no phantom WAVs for the sweep to resurrect", wavsIn(recordings).isEmpty())
        assertFalse("staging must be wiped on failure", staging.exists())
    }

    @Test
    fun successfulImportMovesAudioAndInsertsWithFinalPaths() {
        val imp = importer()
        val zip = archiveOf(
            Triple("first entry", 100L, byteArrayOf(1, 1, 1)),
            Triple("second entry", 200L, byteArrayOf(2, 2)),
        )

        val inserted = mutableListOf<JournalArchive.ImportedEntry>()
        val r = imp.import(ByteArrayInputStream(zip), emptySet()) { inserted += it }

        assertEquals(JournalImporter.Result(added = 2, total = 2), r)
        assertEquals(2, inserted.size)
        for (e in inserted) {
            val f = File(e.audioPath!!)
            assertEquals(recordings, f.parentFile)
            assertTrue(f.name.startsWith("imported_"))
        }
        assertArrayEquals(byteArrayOf(1, 1, 1), File(inserted[0].audioPath!!).readBytes())
        assertFalse("staging must be wiped on success", staging.exists())
    }

    @Test
    fun duplicatesAreSkippedAndTheirAudioNeverReachesRecordings() {
        val imp = importer()
        val zip = archiveOf(
            Triple("already here", 100L, byteArrayOf(9)),
            Triple("new one", 200L, byteArrayOf(8)),
        )

        val inserted = mutableListOf<JournalArchive.ImportedEntry>()
        val r = imp.import(
            ByteArrayInputStream(zip),
            existingKeys = setOf(100L to "already here"),
        ) { inserted += it }

        assertEquals(JournalImporter.Result(added = 1, total = 2), r)
        assertEquals("new one", inserted.single().transcript)
        assertEquals(1, wavsIn(recordings).size)
    }

    @Test
    fun insertFailureRemovesThatEntrysAudioAndKeepsEarlierOnes() {
        val imp = importer()
        val zip = archiveOf(
            Triple("lands fine", 100L, byteArrayOf(1)),
            Triple("insert explodes", 200L, byteArrayOf(2)),
            Triple("never reached", 300L, byteArrayOf(3)),
        )

        val inserted = mutableListOf<JournalArchive.ImportedEntry>()
        try {
            imp.import(ByteArrayInputStream(zip), emptySet()) { e ->
                if (e.transcript == "insert explodes") error("db said no")
                inserted += e
            }
            fail("expected the insert exception to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("db said no", e.message)
        }

        // First entry is fully consistent: inserted, audio present.
        assertEquals(listOf("lands fine"), inserted.map { it.transcript })
        val kept = wavsIn(recordings)
        assertEquals("only the inserted entry's audio remains", 1, kept.size)
        assertArrayEquals(byteArrayOf(1), kept.single().readBytes())
        assertFalse(staging.exists())
    }

    @Test
    fun repeatedImportsOfTheSameArchiveDoNotCollideOnFilenames() {
        val imp = importer()
        val zip = archiveOf(Triple("same name twice", 100L, byteArrayOf(7)))

        imp.import(ByteArrayInputStream(zip), emptySet()) { }
        // Second run: not a duplicate (caller passes keys; here none), so the
        // same archive member name must land under a fresh filename.
        imp.import(ByteArrayInputStream(zip), emptySet()) { }

        val files = wavsIn(recordings)
        assertEquals(2, files.size)
        assertEquals(2, files.map { it.name }.toSet().size)
    }

    @Test
    fun staleStagingFromACrashedImportIsClearedNotLeaked() {
        val imp = importer()
        staging.mkdirs()
        File(staging, "leftover.wav").writeBytes(ByteArray(100))

        val zip = archiveOf(Triple("fresh", 100L, byteArrayOf(5)))
        val r = imp.import(ByteArrayInputStream(zip), emptySet()) { }

        assertEquals(1, r.added)
        assertEquals(1, wavsIn(recordings).size)
        assertFalse("stale leftovers must not survive or migrate", staging.exists())
    }
}
