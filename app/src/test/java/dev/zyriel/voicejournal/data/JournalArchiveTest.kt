package dev.zyriel.voicejournal.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

class JournalArchiveTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun entry(id: Long, text: String, ts: Long, audio: String) =
        JournalEntry(id = id, transcript = text, timestampMs = ts, audioPath = audio)

    private fun wav(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun sinkInto(dir: File): (String, java.io.InputStream) -> String? = { name, data ->
        val f = File(dir, name)
        f.outputStream().use { data.copyTo(it) }
        f.absolutePath
    }

    @Test
    fun roundTripPreservesTranscriptsTimestampsAndAudioBytes() {
        val a1 = wav("entry_20260701_090000.wav", byteArrayOf(1, 2, 3, 4, 5))
        val a2 = wav("entry_20260702_183000.wav", byteArrayOf(9, 8, 7))
        val entries = listOf(
            entry(1, "walked the long way home", 1_750_000_000_000, a1.absolutePath),
            entry(2, "container finally cooperated", 1_750_086_400_000, a2.absolutePath),
        )

        val zipBytes = ByteArrayOutputStream().also { out ->
            JournalArchive.export(entries, { File(it.audioPath) }, out, appVersion = "0.4.1-test")
        }.toByteArray()

        val importDir = tmp.newFolder("imported")
        val imported = JournalArchive.import(ByteArrayInputStream(zipBytes), sinkInto(importDir))

        assertEquals(2, imported.size)
        assertEquals("walked the long way home", imported[0].transcript)
        assertEquals(1_750_000_000_000, imported[0].timestampMs)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), File(imported[0].audioPath!!).readBytes())
        assertArrayEquals(byteArrayOf(9, 8, 7), File(imported[1].audioPath!!).readBytes())
    }

    @Test
    fun entryWithMissingAudioExportsTranscriptOnly() {
        val gone = File(tmp.root, "never_existed.wav")
        val entries = listOf(entry(1, "audio lost to a cleanup", 42, gone.absolutePath))

        val zipBytes = ByteArrayOutputStream().also { out ->
            JournalArchive.export(entries, { File(it.audioPath) }, out, appVersion = "t")
        }.toByteArray()

        val imported = JournalArchive.import(ByteArrayInputStream(zipBytes), sinkInto(tmp.newFolder()))
        assertEquals(1, imported.size)
        assertEquals("audio lost to a cleanup", imported[0].transcript)
        assertNull(imported[0].audioPath)
    }

    @Test
    fun duplicateAudioFileNamesDoNotCollideInsideTheArchive() {
        // Two entries whose source WAVs share a name (e.g. restored from
        // different devices). The index prefix must keep them distinct.
        val d1 = tmp.newFolder("a"); val d2 = tmp.newFolder("b")
        val a1 = File(d1, "entry_same.wav").apply { writeBytes(byteArrayOf(1)) }
        val a2 = File(d2, "entry_same.wav").apply { writeBytes(byteArrayOf(2)) }
        val entries = listOf(
            entry(1, "first", 1, a1.absolutePath),
            entry(2, "second", 2, a2.absolutePath),
        )

        val zipBytes = ByteArrayOutputStream().also { out ->
            JournalArchive.export(entries, { File(it.audioPath) }, out, appVersion = "t")
        }.toByteArray()

        val seen = ArrayList<ByteArray>()
        val imported = JournalArchive.import(ByteArrayInputStream(zipBytes)) { name, data ->
            val f = File(tmp.newFolder(), name)
            f.outputStream().use { data.copyTo(it) }
            seen += f.readBytes()
            f.absolutePath
        }
        assertEquals(2, imported.size)
        assertArrayEquals(byteArrayOf(1), File(imported[0].audioPath!!).readBytes())
        assertArrayEquals(byteArrayOf(2), File(imported[1].audioPath!!).readBytes())
        assertEquals(2, seen.size)
    }

    @Test
    fun zipWithoutManifestIsRejected() {
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("audio/0_x.wav")); z.write(byteArrayOf(0)); z.closeEntry()
            }
        }.toByteArray()

        try {
            JournalArchive.import(ByteArrayInputStream(zipBytes), sinkInto(tmp.newFolder()))
            fail("expected ArchiveFormatException")
        } catch (e: JournalArchive.ArchiveFormatException) {
            assertTrue(e.message!!.contains("manifest"))
        }
    }

    @Test
    fun unsupportedFormatVersionIsRejected() {
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("""{"formatVersion": 999, "entries": []}""".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()

        try {
            JournalArchive.import(ByteArrayInputStream(zipBytes), sinkInto(tmp.newFolder()))
            fail("expected ArchiveFormatException")
        } catch (e: JournalArchive.ArchiveFormatException) {
            assertTrue(e.message!!.contains("999"))
        }
    }

    @Test
    fun garbageInputIsRejectedNotCrashed() {
        val garbage = ByteArray(64) { (it * 7).toByte() }
        try {
            JournalArchive.import(ByteArrayInputStream(garbage), sinkInto(tmp.newFolder()))
            fail("expected ArchiveFormatException")
        } catch (e: JournalArchive.ArchiveFormatException) {
            // ZipInputStream yields no entries from garbage, so this lands
            // on the missing-manifest path rather than an IO crash.
            assertTrue(e.message!!.contains("manifest"))
        }
    }

    @Test
    fun craftedAudioMemberNamesCannotEscapeTheSinkDirectory() {
        // A hostile archive with "audio/../../evil.wav" must reach the sink
        // as a bare filename, never with path components.
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write(
                    """{"formatVersion": 1, "entries":
                       [{"transcript":"t","timestampMs":1,"audio":"audio/../../evil.wav"}]}"""
                        .toByteArray()
                )
                z.closeEntry()
                z.putNextEntry(ZipEntry("audio/../../evil.wav")); z.write(byteArrayOf(1)); z.closeEntry()
            }
        }.toByteArray()

        val names = ArrayList<String>()
        JournalArchive.import(ByteArrayInputStream(zipBytes)) { name, data ->
            names += name
            data.readBytes()
            null // drop it
        }
        assertEquals(listOf("evil.wav"), names)
    }

    @Test
    fun manifestPlacedAfterAudioStillImports() {
        // Zips repacked by other tools can reorder members; import must not
        // depend on manifest-first ordering.
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("audio/0_late.wav")); z.write(byteArrayOf(5, 5)); z.closeEntry()
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write(
                    """{"formatVersion": 1, "entries":
                       [{"transcript":"late manifest","timestampMs":7,"audio":"audio/0_late.wav"}]}"""
                        .toByteArray()
                )
                z.closeEntry()
            }
        }.toByteArray()

        val imported = JournalArchive.import(ByteArrayInputStream(zipBytes), sinkInto(tmp.newFolder()))
        assertEquals(1, imported.size)
        assertEquals("late manifest", imported[0].transcript)
        assertArrayEquals(byteArrayOf(5, 5), File(imported[0].audioPath!!).readBytes())
    }
}
