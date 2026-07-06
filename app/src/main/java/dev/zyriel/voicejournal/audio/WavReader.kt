package dev.zyriel.voicejournal.audio

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a WAV produced by [WavWriter] into normalized 32-bit float PCM,
 * which is the input format whisper.cpp expects. Pure Kotlin, JVM-testable.
 *
 * Streams from disk: the only large allocation is the FloatArray itself
 * (4 bytes per sample), never a second full copy of the file. A one-hour
 * recording still needs ~230 MB of floats, which is why callers pass
 * [readFloatPcm]'s maxSamples — the reader enforces the ceiling before
 * allocating anything proportional to file size.
 */
object WavReader {

    private const val CHUNK_BYTES = 64 * 1024

    /**
     * Returns samples in [-1.0, 1.0]. Throws on anything that isn't our
     * canonical format, and on audio longer than [maxSamples] samples —
     * the check runs before the sample buffer is allocated, so an
     * over-length file fails fast instead of exhausting the heap.
     */
    fun readFloatPcm(file: File, maxSamples: Int = Int.MAX_VALUE): FloatArray {
        require(file.length() >= WavWriter.HEADER_SIZE) { "File too small to be a WAV" }

        DataInputStream(file.inputStream().buffered(CHUNK_BYTES)).use { input ->
            val header = ByteArray(WavWriter.HEADER_SIZE)
            input.readFully(header)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            require(ascii(header, 0, 4) == "RIFF" && ascii(header, 8, 4) == "WAVE") { "Not a RIFF/WAVE file" }
            require(h.getShort(20).toInt() == 1) { "Not PCM" }
            require(h.getShort(22).toInt() == 1) { "Not mono" }
            require(h.getInt(24) == WavWriter.SAMPLE_RATE) { "Not 16 kHz" }
            require(h.getShort(34).toInt() == 16) { "Not 16-bit" }
            // The canonical header puts the data chunk at offset 36. A WAV
            // from any other writer can carry a metadata chunk there (ffmpeg
            // emits LIST/INFO), and reading its size field as the data size
            // silently yields a few samples of ASCII-as-audio. Foreign
            // layouts must fail loudly, per this function's contract.
            require(ascii(header, 36, 4) == "data") {
                "Expected data chunk at offset 36, found '${ascii(header, 36, 4)}' (non-canonical WAV)"
            }

            val dataSize = h.getInt(40)
            require(dataSize >= 0 && WavWriter.HEADER_SIZE + dataSize.toLong() <= file.length()) {
                "data chunk exceeds file size"
            }

            val samples = dataSize / 2
            require(samples <= maxSamples) {
                "audio is $samples samples (${samples / WavWriter.SAMPLE_RATE}s), " +
                    "over the $maxSamples-sample transcription limit"
            }

            val out = FloatArray(samples)
            val chunk = ByteArray(CHUNK_BYTES)
            var i = 0
            var remaining = samples * 2
            while (remaining > 0) {
                val want = minOf(chunk.size, remaining)
                input.readFully(chunk, 0, want)
                val cb = ByteBuffer.wrap(chunk, 0, want).order(ByteOrder.LITTLE_ENDIAN)
                repeat(want / 2) {
                    out[i++] = cb.short / 32768f
                }
                remaining -= want
            }
            return out
        }
    }

    private fun ascii(b: ByteArray, at: Int, len: Int) = String(b, at, len, Charsets.US_ASCII)
}
