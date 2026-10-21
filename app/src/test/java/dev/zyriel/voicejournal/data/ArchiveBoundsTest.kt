package dev.zyriel.voicejournal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ceilings on the parts of an archive that used to be unbounded. */
class ArchiveBoundsTest {

    private val manifestOk = """{"formatVersion": 1, "entries": [{"transcript": "hi", "timestampMs": 1}]}"""

    private fun zip(build: ZipOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { it.build() } }.toByteArray()

    private fun ZipOutputStream.member(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
    }

    private fun rejects(bytes: ByteArray, expect: String, call: (ByteArray) -> Unit = { importOf(it) }) {
        try {
            call(bytes)
            fail("expected ArchiveFormatException mentioning '$expect'")
        } catch (e: JournalArchive.ArchiveFormatException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(expect))
        }
    }

    private fun importOf(
        bytes: ByteArray,
        maxManifestBytes: Long = JournalArchive.DEFAULT_MAX_MANIFEST_BYTES,
        maxMembers: Int = JournalArchive.DEFAULT_MAX_MEMBERS,
        maxEntries: Int = JournalArchive.DEFAULT_MAX_ENTRIES,
    ) = JournalArchive.import(
        ByteArrayInputStream(bytes),
        maxManifestBytes = maxManifestBytes,
        maxMembers = maxMembers,
        maxEntries = maxEntries,
    ) { _, data -> data.copyTo(java.io.OutputStream.nullOutputStream()); null }

    @Test
    fun manifestBombIsCutOffAtTheCeilingNotReadWhole() {
        // 4 MB of zeros deflates to a few KB: the classic bomb shape.
        val bomb = zip { member("manifest.json", ByteArray(4 * 1024 * 1024)) }
        assertTrue("fixture should be small on the wire", bomb.size < 64 * 1024)
        rejects(bomb, "size limit") { importOf(it, maxManifestBytes = 1024 * 1024) }
    }

    @Test
    fun manifestUnderTheCeilingStillImports() {
        val ok = zip { member("manifest.json", manifestOk.toByteArray()) }
        assertEquals(1, importOf(ok, maxManifestBytes = 1024).size)
    }

    @Test
    fun secondManifestIsRejected() {
        // ZipOutputStream refuses duplicate names, so write a same-length
        // stand-in and patch its name bytes (local header and central dir).
        val raw = zip {
            member("manifest.json", manifestOk.toByteArray())
            member("manifest.jsoN", """{"formatVersion": 1, "entries": []}""".toByteArray())
        }
        val twice = String(raw, Charsets.ISO_8859_1).replace("manifest.jsoN", "manifest.json")
            .toByteArray(Charsets.ISO_8859_1)
        rejects(twice, "more than one manifest.json")
    }

    @Test
    fun memberCountIsCapped() {
        val many = zip {
            member("manifest.json", manifestOk.toByteArray())
            repeat(10) { member("junk/$it", byteArrayOf(1)) }
        }
        rejects(many, "more than 5 members") { importOf(it, maxMembers = 5) }
        assertEquals(1, importOf(many, maxMembers = 11).size)
    }

    @Test
    fun hugeEntriesArrayIsRejectedBeforeItIsWalked() {
        val entries = (1..20).joinToString(",") { """{"transcript": "t$it", "timestampMs": $it}""" }
        val big = zip { member("manifest.json", """{"formatVersion": 1, "entries": [$entries]}""".toByteArray()) }
        rejects(big, "limit 10") { importOf(it, maxEntries = 10) }
    }

    @Test
    fun declaredOversizeAudioFailsWithoutReadingIt() {
        // STORED members carry their real size in the header, so the
        // declared-size shortcut fires before a byte is streamed.
        val payload = ByteArray(2048) { 7 }
        val crc = java.util.zip.CRC32().apply { update(payload) }
        val stored = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.member("manifest.json", manifestOk.toByteArray())
                val e = ZipEntry("audio/0_x.wav").apply {
                    method = ZipEntry.STORED; size = payload.size.toLong(); compressedSize = size; this.crc = crc.value
                }
                z.putNextEntry(e); z.write(payload); z.closeEntry()
            }
        }.toByteArray()
        var sinkCalls = 0
        try {
            JournalArchive.import(ByteArrayInputStream(stored), maxAudioMemberBytes = 1024) { _, _ -> sinkCalls++; null }
            fail("expected ArchiveFormatException")
        } catch (e: JournalArchive.ArchiveFormatException) {
            assertEquals("sink must never see an oversize member", 0, sinkCalls)
        }
    }
}
