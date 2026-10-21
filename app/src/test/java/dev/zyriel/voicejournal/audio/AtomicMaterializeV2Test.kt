package dev.zyriel.voicejournal.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Length validation, per-call temp files and stale-sibling cleanup, added
 * with the review's VAD materialization race fix. The original behavior
 * stays covered by AtomicMaterializeTest.
 */
class AtomicMaterializeV2Test {

    @get:Rule val tmp = TemporaryFolder()

    private fun managed(name: String) = name.startsWith("ggml-") && name.endsWith(".bin")

    @Test
    fun wrongLengthDestinationIsReplaced() {
        val dest = tmp.newFile("model.bin").apply { writeBytes(ByteArray(10)) }
        val payload = ByteArray(32) { it.toByte() }

        AtomicMaterialize.ensure(dest, payload.size.toLong()) { ByteArrayInputStream(payload) }

        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun rightLengthDestinationIsNotReopened() {
        val dest = tmp.newFile("model.bin").apply { writeBytes(ByteArray(8) { 3 }) }
        var opened = false

        AtomicMaterialize.ensure(dest, 8) { opened = true; ByteArrayInputStream(ByteArray(8)) }

        assertFalse(opened)
    }

    @Test
    fun shortCopyIsRejectedAndNothingIsPublished() {
        val dest = File(tmp.root, "model.bin")

        assertThrows(IOException::class.java) {
            AtomicMaterialize.ensure(dest, 100) { ByteArrayInputStream(ByteArray(60)) }
        }

        assertFalse(dest.exists())
        assertTrue("no temp file may remain", tmp.root.listFiles()!!.isEmpty())
    }

    @Test
    fun concurrentWritersCannotTearTheFile() {
        // The old fixed .part name let writer B truncate writer A's file
        // mid-copy. With per-call temp files both copies are whole, and
        // whichever rename lands last publishes a complete file.
        val dest = File(tmp.root, "model.bin")
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val bothOpen = CountDownLatch(2)

        class SlowStream : InputStream() {
            private val inner = ByteArrayInputStream(payload)
            private var first = true
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) { first = false; bothOpen.countDown(); bothOpen.await() }
                return inner.read(b, off, minOf(len, 4096))
            }
        }

        val errors = mutableListOf<Throwable>()
        val threads = List(2) {
            thread {
                try {
                    AtomicMaterialize.ensure(dest, payload.size.toLong()) { SlowStream() }
                } catch (t: Throwable) {
                    // A rename that loses the race is acceptable; a torn file is not.
                    synchronized(errors) { errors += t }
                }
            }
        }
        threads.forEach { it.join() }

        assertTrue("at most one writer may fail: $errors", errors.size <= 1)
        assertArrayEquals(payload, dest.readBytes())
        assertEquals(listOf("model.bin"), tmp.root.list()!!.toList())
    }

    @Test
    fun staleModelsAndTempFilesAreDeletedKeptOnesSurvive() {
        val dir = tmp.root
        listOf(
            "ggml-base.en-q5_1.bin",       // kept
            "ggml-silero-v6.2.0.bin",      // kept
            "ggml-silero-v5.1.2.bin",      // stale after a bump
            "ggml-tiny.en.bin",            // stale
            "ggml-base.en-q5_1.bin.part",  // legacy fixed temp
            "ggml-silero-v6.2.0.bin123456789.part", // createTempFile leftover
            "vj-benchmark-abc.json",       // not ours
            "unrelated.bin",               // not ours
        ).forEach { File(dir, it).writeBytes(byteArrayOf(1)) }
        File(dir, "recordings").mkdirs()

        val failed = AtomicMaterialize.deleteStaleSiblings(
            dir, setOf("ggml-base.en-q5_1.bin", "ggml-silero-v6.2.0.bin"), ::managed,
        )

        assertTrue(failed.isEmpty())
        assertEquals(
            setOf(
                "ggml-base.en-q5_1.bin", "ggml-silero-v6.2.0.bin",
                "vj-benchmark-abc.json", "unrelated.bin", "recordings",
            ),
            dir.list()!!.toSet(),
        )
    }

    @Test
    fun missingDirectoryIsNotAnError() {
        val failed = AtomicMaterialize.deleteStaleSiblings(File(tmp.root, "nope"), emptySet(), ::managed)
        assertTrue(failed.isEmpty())
    }
}
