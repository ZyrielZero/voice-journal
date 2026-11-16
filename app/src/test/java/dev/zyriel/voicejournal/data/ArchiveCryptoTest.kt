package dev.zyriel.voicejournal.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class ArchiveCryptoTest {

    // Few iterations: these tests are about the container, not the KDF cost.
    private val iters = 1_000
    private val pw get() = "correct horse".toCharArray()

    private fun seal(plain: ByteArray, password: CharArray = pw): ByteArray {
        val bos = ByteArrayOutputStream()
        ArchiveCrypto.encrypting(bos, password, iters).use { it.write(plain) }
        return bos.toByteArray()
    }

    private fun open(sealed: ByteArray, password: CharArray = pw): ByteArray =
        ArchiveCrypto.decrypting(ByteArrayInputStream(sealed), password).use { it.readBytes() }

    @Test
    fun roundTripsAtEveryFrameBoundary() {
        val f = ArchiveCrypto.FRAME_BYTES
        for (size in listOf(0, 1, f - 1, f, f + 1, 2 * f, 3 * f + 123)) {
            val plain = Random(size).nextBytes(size)
            assertArrayEquals("size $size", plain, open(seal(plain)))
        }
    }

    @Test
    fun roundTripsWhenWrittenByteByByteAndInOddChunks() {
        val plain = Random(7).nextBytes(ArchiveCrypto.FRAME_BYTES + 500)
        val bos = ByteArrayOutputStream()
        ArchiveCrypto.encrypting(bos, pw, iters).use { out ->
            out.write(plain[0].toInt())
            var off = 1
            var n = 1
            while (off < plain.size) {
                val len = minOf(n, plain.size - off)
                out.write(plain, off, len)
                off += len
                n = n * 3 + 1
            }
        }
        assertArrayEquals(plain, open(bos.toByteArray()))
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val plain = "my private journal entry ".repeat(50).toByteArray()
        val sealed = seal(plain)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("private journal"))
    }

    @Test
    fun sameInputEncryptsDifferentlyEachTime() {
        val plain = ByteArray(100) { 1 }
        assertFalse(seal(plain).contentEquals(seal(plain)))
    }

    @Test
    fun wrongPasswordFailsBeforeAnyDataIsReturned() {
        val sealed = seal(ByteArray(1000) { 3 })
        assertThrows(ArchiveCrypto.WrongPasswordException::class.java) {
            ArchiveCrypto.decrypting(ByteArrayInputStream(sealed), "wrong".toCharArray())
        }
    }

    @Test
    fun aFlippedBitAnywhereFailsTheRead() {
        val plain = Random(1).nextBytes(ArchiveCrypto.FRAME_BYTES * 2 + 10)
        val sealed = seal(plain)
        // Header, first frame, a later frame, the very last byte.
        for (i in listOf(5, 12, 40, sealed.size / 2, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertThrows("byte $i", IOException::class.java) { open(bad) }
        }
    }

    @Test
    fun truncationIsDetectedEvenAtAFrameBoundary() {
        val f = ArchiveCrypto.FRAME_BYTES
        val sealed = seal(Random(2).nextBytes(f * 2 + 10))
        val headerLen = 32
        val frameLen = 4 + f + 16
        // Cut exactly after the first whole frame, and mid-frame.
        assertThrows(IOException::class.java) { open(sealed.copyOf(headerLen + frameLen)) }
        assertThrows(IOException::class.java) { open(sealed.copyOf(headerLen + frameLen + 100)) }
        assertThrows(IOException::class.java) { open(sealed.copyOf(headerLen)) }
        assertThrows(IOException::class.java) { open(sealed.copyOf(10)) }
    }

    @Test
    fun appendedDataIsRejected() {
        val sealed = seal(ByteArray(50) { 9 })
        assertThrows(IOException::class.java) { open(sealed + ByteArray(40)) }
    }

    @Test
    fun framesCannotBeSwapped() {
        val f = ArchiveCrypto.FRAME_BYTES
        val sealed = seal(Random(3).nextBytes(f * 3))
        val headerLen = 32
        val frameLen = 4 + f + 16
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, headerLen + frameLen, swapped, headerLen, frameLen)
        System.arraycopy(sealed, headerLen, swapped, headerLen + frameLen, frameLen)
        assertThrows(IOException::class.java) { open(swapped) }
    }

    @Test
    fun detectsEncryptedArchivesWithoutConsumingTheStream() {
        val sealed = BufferedInputStream(ByteArrayInputStream(seal(ByteArray(10))))
        assertTrue(ArchiveCrypto.isEncrypted(sealed))
        assertEquals('V'.code, sealed.read()) // still at the start

        val zip = BufferedInputStream(ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0)))
        assertFalse(ArchiveCrypto.isEncrypted(zip))
        assertEquals(0x50, zip.read())

        assertFalse(ArchiveCrypto.isEncrypted(BufferedInputStream(ByteArrayInputStream(ByteArray(2)))))
    }

    @Test
    fun anAbsurdIterationCountIsRefused() {
        val sealed = seal(ByteArray(10))
        // iterations live at bytes 5..8, big-endian
        val bad = sealed.copyOf().also { it[5] = 0x7F }
        assertThrows(IOException::class.java) { open(bad) }
    }

    @Test
    fun encryptedJournalArchiveImportsBack() {
        val entries = listOf(
            JournalEntry(id = 1, transcript = "first entry", timestampMs = 1000, audioPath = ""),
            JournalEntry(id = 2, transcript = "second entry", timestampMs = 2000, audioPath = ""),
        )
        val bos = ByteArrayOutputStream()
        JournalArchive.export(entries, { null }, ArchiveCrypto.encrypting(bos, pw, iters), "test")
        val back = JournalArchive.import(ArchiveCrypto.decrypting(ByteArrayInputStream(bos.toByteArray()), pw)) { _, _ -> null }
        assertEquals(listOf("first entry", "second entry"), back.map { it.transcript })
    }
}
