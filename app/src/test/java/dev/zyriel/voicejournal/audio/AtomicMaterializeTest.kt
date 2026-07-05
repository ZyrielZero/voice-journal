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
import java.io.IOException
import java.io.InputStream

class AtomicMaterializeTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Stream that yields [good] bytes then dies, simulating process death mid-copy. */
    private class DyingStream(private val good: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int =
            if (pos < good.size) good[pos++].toInt() and 0xFF
            else throw IOException("simulated death mid-copy")
    }

    @Test
    fun successfulCopyLandsCompleteAtDestination() {
        val dest = java.io.File(tmp.root, "model.bin")
        val payload = ByteArray(200_000) { (it % 251).toByte() }

        AtomicMaterialize.ensure(dest) { ByteArrayInputStream(payload) }

        assertArrayEquals(payload, dest.readBytes())
        assertFalse(java.io.File(tmp.root, "model.bin.part").exists())
    }

    @Test
    fun failureMidCopyLeavesNoFileAtDestination() {
        val dest = java.io.File(tmp.root, "model.bin")

        assertThrows(IOException::class.java) {
            AtomicMaterialize.ensure(dest) { DyingStream(ByteArray(1000)) }
        }

        // This is the whole point: the old pattern left a truncated file
        // here that passed exists-and-nonempty checks forever.
        assertFalse(dest.exists())
        assertFalse(java.io.File(tmp.root, "model.bin.part").exists())
    }

    @Test
    fun retryAfterFailureSucceeds() {
        val dest = java.io.File(tmp.root, "model.bin")
        runCatching { AtomicMaterialize.ensure(dest) { DyingStream(ByteArray(64)) } }

        val payload = byteArrayOf(1, 2, 3)
        AtomicMaterialize.ensure(dest) { ByteArrayInputStream(payload) }
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun existingCompleteDestinationIsNotReopened() {
        val dest = tmp.newFile("model.bin").apply { writeBytes(byteArrayOf(9, 9)) }
        var opened = false

        AtomicMaterialize.ensure(dest) { opened = true; ByteArrayInputStream(byteArrayOf(1)) }

        assertFalse("stream must not be opened when dest is already complete", opened)
        assertArrayEquals(byteArrayOf(9, 9), dest.readBytes())
    }

    @Test
    fun legacyZeroByteDestinationIsReplaced() {
        val dest = tmp.newFile("model.bin") // zero bytes: the old pattern's corpse
        assertEquals(0L, dest.length())

        AtomicMaterialize.ensure(dest) { ByteArrayInputStream(byteArrayOf(4, 5, 6)) }
        assertArrayEquals(byteArrayOf(4, 5, 6), dest.readBytes())
    }

    @Test
    fun staleDotPartFromEarlierDeathIsOverwritten() {
        val dest = java.io.File(tmp.root, "model.bin")
        java.io.File(tmp.root, "model.bin.part").writeBytes(ByteArray(50) { 1 })

        AtomicMaterialize.ensure(dest) { ByteArrayInputStream(byteArrayOf(7)) }

        assertArrayEquals(byteArrayOf(7), dest.readBytes())
        assertFalse(java.io.File(tmp.root, "model.bin.part").exists())
    }

    @Test
    fun destUnaffectedIfOpenItselfThrows() {
        val dest = java.io.File(tmp.root, "model.bin")
        assertThrows(IllegalStateException::class.java) {
            AtomicMaterialize.ensure(dest) { error("asset missing") }
        }
        assertFalse(dest.exists())
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }
}
