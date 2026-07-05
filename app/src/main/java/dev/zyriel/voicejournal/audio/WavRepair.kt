package dev.zyriel.voicejournal.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Detects and repairs WAV files orphaned by process death mid-recording.
 *
 * [WavWriter] writes its header with zeroed chunk sizes at init and patches
 * them in [WavWriter.close]. If the process dies while recording, the file on
 * disk has a structurally valid header that declares zero data, with real PCM
 * sitting after byte 44. [WavReader] trusts the declared size, so such a file
 * silently reads as empty. Both size fields are derivable from file length,
 * which is what this object does.
 *
 * Deliberately narrow: only touches files matching our writer's exact
 * canonical layout (44-byte header, PCM, mono, 16 kHz, 16-bit). Anything else
 * is [Outcome.Unrecoverable] and is left byte-for-byte untouched, because a
 * file we didn't write is a file we have no business patching.
 *
 * Pure Kotlin/JVM, no Android imports, unit-tested off-device.
 */
object WavRepair {

    sealed interface Outcome {
        /** Header sizes already match the file. Nothing was modified. */
        data object AlreadyConsistent : Outcome

        /** Header was patched. [recoveredSamples] is the sample count now readable. */
        data class Repaired(val recoveredSamples: Long) : Outcome

        /** Not our format, too small, or contains no audio. File left unmodified
         *  except for [reason] "no audio data", where deletion is the caller's call. */
        data class Unrecoverable(val reason: String) : Outcome
    }

    /**
     * Inspects [file] and patches its RIFF/data chunk sizes if they disagree
     * with the actual file length. A trailing odd byte (a half-written sample
     * from the moment of death) is truncated first, since block align is 2.
     */
    fun inspectAndRepair(file: File): Outcome {
        val len = file.length()
        if (len < WavWriter.HEADER_SIZE) {
            return Outcome.Unrecoverable("file smaller than a WAV header ($len bytes)")
        }

        val header = ByteArray(WavWriter.HEADER_SIZE)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        if (ascii(header, 0) != "RIFF" || ascii(header, 8) != "WAVE" ||
            ascii(header, 12) != "fmt " || ascii(header, 36) != "data"
        ) {
            return Outcome.Unrecoverable("not a canonical WavWriter header")
        }
        if (h.getShort(20).toInt() != 1 ||
            h.getShort(22).toInt() != WavWriter.CHANNELS ||
            h.getInt(24) != WavWriter.SAMPLE_RATE ||
            h.getShort(34).toInt() != WavWriter.BITS_PER_SAMPLE
        ) {
            return Outcome.Unrecoverable("format fields are not ours")
        }

        var dataBytes = len - WavWriter.HEADER_SIZE
        val hasOddTail = dataBytes % 2L != 0L
        if (hasOddTail) dataBytes -= 1

        if (dataBytes == 0L) {
            return Outcome.Unrecoverable("no audio data")
        }
        // Mirror WavWriter's ceiling: it refuses to finalize a header past
        // this size, so a larger file cannot be one of ours and patching
        // its sizes would silently truncate through Int overflow.
        if (dataBytes > Int.MAX_VALUE - 36) {
            return Outcome.Unrecoverable("data exceeds WAV size limit; not a WavWriter file")
        }

        val declaredData = h.getInt(40).toLong()
        val declaredRiff = h.getInt(4).toLong()
        if (!hasOddTail && declaredData == dataBytes && declaredRiff == 36 + dataBytes) {
            return Outcome.AlreadyConsistent
        }

        RandomAccessFile(file, "rw").use { raf ->
            if (hasOddTail) raf.setLength(WavWriter.HEADER_SIZE + dataBytes)
            val patch = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            raf.seek(4)
            raf.write(patch.putInt((36 + dataBytes).toInt()).array())
            patch.clear()
            raf.seek(40)
            raf.write(patch.putInt(dataBytes.toInt()).array())
        }
        return Outcome.Repaired(dataBytes / 2)
    }

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
}
