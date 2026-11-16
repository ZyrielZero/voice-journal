package dev.zyriel.voicejournal.data

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional password protection for an exported journal. The export zip is
 * the one copy of the journal that leaves app-private storage, so it is
 * the one place where encryption at rest is the user's only protection.
 *
 * The encrypted file is a small container around the ordinary zip:
 *
 *   header   "VJE1" | kdf id (1 byte) | iterations (4 bytes, big-endian)
 *            | salt (16 bytes) | nonce prefix (7 bytes)
 *   frames   length (4 bytes, big-endian) | AES-256-GCM ciphertext and tag
 *
 * The key is PBKDF2-HMAC-SHA256 of the password. The zip is sealed in
 * frames of at most [FRAME_BYTES] plaintext bytes so a multi-gigabyte
 * archive streams in constant memory and nothing is released to the
 * importer before its frame has been authenticated. Each frame's nonce is
 * the prefix, a frame counter and a last-frame flag (the STREAM
 * construction), and the header is bound to every frame as associated
 * data, so frames cannot be reordered, dropped, replayed from another
 * file, or cut off at the end without the import failing.
 *
 * Standard javax.crypto only; pure JVM, so the format is unit-tested.
 */
object ArchiveCrypto {

    private val MAGIC = byteArrayOf('V'.code.toByte(), 'J'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 7
    private const val HEADER_BYTES = 4 + 1 + 4 + SALT_BYTES + PREFIX_BYTES
    private const val TAG_BYTES = 16
    private const val KEY_BITS = 256

    /** Plaintext bytes per frame. */
    const val FRAME_BYTES = 64 * 1024

    /** OWASP's 2023 floor for PBKDF2-HMAC-SHA256; about a second on a phone. */
    const val DEFAULT_ITERATIONS = 600_000

    /** Refused on import: a crafted header must not buy minutes of CPU. */
    private const val MAX_ITERATIONS = 10_000_000

    /** The password does not open this file (or the file is damaged at its start). */
    class WrongPasswordException : IOException("Wrong password, or the file is damaged.")

    /** The file is encrypted and no password was supplied. */
    class PasswordRequiredException : IOException("This archive is password-protected.")

    /**
     * True when [inp] starts with the encrypted-archive magic. Reads and
     * then un-reads the first four bytes, so the stream is left where it
     * was; [inp] must support mark (a [BufferedInputStream] does).
     */
    fun isEncrypted(inp: InputStream): Boolean {
        require(inp.markSupported()) { "stream must support mark" }
        inp.mark(MAGIC.size)
        val head = ByteArray(MAGIC.size)
        val n = readUpTo(inp, head)
        inp.reset()
        return n == MAGIC.size && head.contentEquals(MAGIC)
    }

    /**
     * Wraps [out] so that everything written is encrypted under
     * [password]. The header is written at once. Closing the returned
     * stream writes the final frame and closes [out]; a stream that is not
     * closed produces a file that fails to import, by design.
     */
    fun encrypting(
        out: OutputStream,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): OutputStream {
        require(password.isNotEmpty()) { "empty password" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).put(KDF_PBKDF2_SHA256).putInt(iterations).put(salt).put(prefix)
            .array()
        val key = deriveKey(password, salt, iterations)
        out.write(header)
        return EncryptingStream(out, key, prefix, header)
    }

    /**
     * Wraps an encrypted archive so that reads return the original zip
     * bytes. The header is read and the first frame authenticated before
     * this returns, so a wrong password fails here, not midway through an
     * import.
     *
     * @throws WrongPasswordException when the first frame does not
     *   authenticate under [password].
     * @throws IOException when the header is malformed or a later frame is
     *   damaged or missing.
     */
    fun decrypting(inp: InputStream, password: CharArray): InputStream {
        val header = ByteArray(HEADER_BYTES)
        if (readUpTo(inp, header) != HEADER_BYTES) throw IOException("encrypted archive is truncated")
        val buf = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("not an encrypted Voice Journal archive")
        val kdf = buf.get()
        if (kdf != KDF_PBKDF2_SHA256) throw IOException("unsupported key derivation ($kdf)")
        val iterations = buf.getInt()
        if (iterations !in 1..MAX_ITERATIONS) throw IOException("unsupported iteration count $iterations")
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val prefix = ByteArray(PREFIX_BYTES).also { buf.get(it) }
        val key = deriveKey(password, salt, iterations)
        return DecryptingStream(inp, key, prefix, header).also { it.primeFirstFrame() }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** prefix (7) | frame counter (4, big-endian) | 1 for the last frame, else 0. */
    private fun nonce(prefix: ByteArray, counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).put(if (last) 1.toByte() else 0.toByte()).array()

    private fun cipher(mode: Int, key: SecretKeySpec, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(aad)
        }

    /** Reads until [b] is full or the stream ends; returns the bytes read. */
    private fun readUpTo(inp: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = inp.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private class EncryptingStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray,
    ) : OutputStream() {
        private val frame = ByteArray(FRAME_BYTES)
        private var filled = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) {
            if (filled == FRAME_BYTES) seal(last = false)
            frame[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                // A full frame is only sealed once more data arrives, so
                // the frame that turns out to be final can be flagged last.
                if (filled == FRAME_BYTES) seal(last = false)
                val n = minOf(left, FRAME_BYTES - filled)
                System.arraycopy(b, o, frame, filled, n)
                filled += n; o += n; left -= n
            }
        }

        private fun seal(last: Boolean) {
            if (counter == Int.MAX_VALUE) throw IOException("archive too large to encrypt")
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, nonce(prefix, counter, last), header)
                .doFinal(frame, 0, filled)
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            counter++
            filled = 0
        }

        override fun flush() = out.flush() // a partial frame stays buffered

        override fun close() {
            if (closed) return
            closed = true
            try {
                seal(last = true) // possibly empty: it is what marks the end
            } finally {
                out.close()
            }
        }
    }

    private class DecryptingStream(
        private val inp: InputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray,
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var sawLast = false
        /** The next frame's length field, read ahead to learn whether the current one is last. */
        private var nextLen = -1

        fun primeFirstFrame() {
            nextLen = readLen() ?: throw IOException("encrypted archive is truncated")
            try {
                nextFrame()
            } catch (e: AEADBadTagException) {
                throw WrongPasswordException()
            }
        }

        private fun readLen(): Int? {
            val b = ByteArray(4)
            val n = readUpTo(inp, b)
            if (n == 0) return null
            if (n != 4) throw EOFException("encrypted archive is truncated")
            val len = ByteBuffer.wrap(b).getInt()
            if (len < TAG_BYTES || len > FRAME_BYTES + TAG_BYTES) throw IOException("encrypted archive is damaged")
            return len
        }

        /** Decrypts the frame whose length is [nextLen]. */
        @Throws(AEADBadTagException::class)
        private fun nextFrame() {
            val sealed = ByteArray(nextLen)
            if (readUpTo(inp, sealed) != sealed.size) throw EOFException("encrypted archive is truncated")
            val following = readLen()
            val last = following == null
            // The flag is part of the nonce: a file cut after a whole frame
            // makes that frame look last here, and its tag then fails.
            plain = cipher(Cipher.DECRYPT_MODE, key, nonce(prefix, counter, last), header).doFinal(sealed)
            pos = 0
            counter++
            sawLast = last
            nextLen = following ?: -1
        }

        private fun fill(): Boolean {
            while (pos == plain.size) {
                if (sawLast) return false
                try {
                    nextFrame()
                } catch (e: AEADBadTagException) {
                    throw IOException("encrypted archive is damaged or incomplete")
                } catch (e: GeneralSecurityException) {
                    throw IOException("could not decrypt archive: ${e.message}")
                }
            }
            return true
        }

        override fun read(): Int = if (fill()) plain[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = inp.close()
    }
}
