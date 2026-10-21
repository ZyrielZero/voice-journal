package dev.zyriel.voicejournal.data

import kotlinx.coroutines.runBlocking
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
import java.io.IOException

/** The C4 sidecar order (sidecar, move, insert, drop sidecar) and the free-space budget. */
class ImporterSidecarTest {

    @get:Rule val tmp = TemporaryFolder()

    private val recordings by lazy { File(tmp.root, "recordings").apply { mkdirs() } }
    private val staging by lazy { File(tmp.root, "staging/run-1") }

    private fun archive(vararg audio: Pair<String, ByteArray>): ByteArray {
        val src = tmp.newFolder()
        val list = audio.mapIndexed { i, (text, bytes) ->
            JournalEntry(
                id = i + 1L, transcript = text, timestampMs = 100L + i,
                audioPath = File(src, "e$i.wav").apply { writeBytes(bytes) }.absolutePath,
            )
        }
        return ByteArrayOutputStream().also {
            JournalArchive.export(list, { e -> File(e.audioPath) }, it, "t")
        }.toByteArray()
    }

    private fun sidecars() = recordings.listFiles()!!.filter { it.name.endsWith(JournalImporter.SIDECAR_SUFFIX) }

    @Test
    fun sidecarExistsWhileInsertingAndIsGoneAfter() = runBlocking {
        val imp = JournalImporter(staging, recordings, freeBytes = { Long.MAX_VALUE })
        val seen = mutableListOf<String>()
        imp.import(ByteArrayInputStream(archive("a" to byteArrayOf(1), "b" to byteArrayOf(2))), emptySet()) { e ->
            val wav = File(e.audioPath!!)
            // At insert time the WAV is already in place AND marked: a death
            // here leaves WAV + sidecar, which the sweep deletes unreferenced.
            assertTrue("wav moved before insert", wav.isFile)
            assertTrue("sidecar created before insert", JournalImporter.sidecarFor(wav).isFile)
            assertEquals(wav.path + ".importing", JournalImporter.sidecarFor(wav).path)
            seen += e.transcript
        }
        assertEquals(listOf("a", "b"), seen)
        assertTrue("every sidecar dropped after its insert", sidecars().isEmpty())
        assertEquals(2, recordings.listFiles()!!.count { it.extension == "wav" })
    }

    @Test
    fun earlierSidecarIsDroppedBeforeTheNextEntryIsMoved() = runBlocking {
        val imp = JournalImporter(staging, recordings, freeBytes = { Long.MAX_VALUE })
        var calls = 0
        imp.import(ByteArrayInputStream(archive("a" to byteArrayOf(1), "b" to byteArrayOf(2))), emptySet()) {
            calls++
            // Only the current entry's sidecar may exist during its insert.
            assertEquals(1, sidecars().size)
        }
        assertEquals(2, calls)
    }

    @Test
    fun failedInsertRemovesBothWavAndSidecar() {
        val imp = JournalImporter(staging, recordings, freeBytes = { Long.MAX_VALUE })
        try {
            runBlocking {
                imp.import(ByteArrayInputStream(archive("boom" to byteArrayOf(1))), emptySet()) { error("db said no") }
            }
            fail("insert failure must propagate")
        } catch (e: IllegalStateException) {
            assertEquals("db said no", e.message)
        }
        assertTrue("nothing left for the sweep", recordings.listFiles()!!.isEmpty())
    }

    @Test
    fun nameCollisionWithALeftoverSidecarPicksAFreshName() = runBlocking {
        // A sidecar alone (a death between sidecar and move) still reserves
        // its name, so the new WAV cannot inherit someone else's marker.
        val imp = JournalImporter(staging, recordings, freeBytes = { Long.MAX_VALUE })
        var path = ""
        val bytes = archive("x" to byteArrayOf(9))
        File(recordings, "imported_0_e0.wav.importing").createNewFile()
        imp.import(ByteArrayInputStream(bytes), emptySet()) { path = it.audioPath!! }
        assertFalse(File(path).name == "imported_0_e0.wav")
    }

    @Test
    fun tooLittleFreeSpaceFailsBeforeStaging() {
        val imp = JournalImporter(staging, recordings, freeBytes = { JournalImporter.FREE_SPACE_RESERVE_BYTES })
        try {
            runBlocking { imp.import(ByteArrayInputStream(archive("a" to byteArrayOf(1))), emptySet()) { fail("no insert") } }
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("free storage"))
        }
        assertFalse("staging wiped", staging.exists())
    }

    @Test
    fun freeSpaceClampsTheTotalAudioBudget() {
        // 4 KB of headroom above the reserve; two 3 KB members exceed it.
        val imp = JournalImporter(staging, recordings, freeBytes = { JournalImporter.FREE_SPACE_RESERVE_BYTES + 4096 })
        try {
            runBlocking {
                imp.import(
                    ByteArrayInputStream(archive("a" to ByteArray(3000) { 1 }, "b" to ByteArray(3000) { 2 })),
                    emptySet(),
                ) { fail("no insert") }
            }
            fail("expected ArchiveFormatException")
        } catch (e: JournalArchive.ArchiveFormatException) {
            // expected
        }
        assertTrue(recordings.listFiles()!!.isEmpty())
    }
}
