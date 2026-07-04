package dev.zyriel.voicejournal.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes 16 kHz mono 16-bit PCM audio to a canonical WAV (RIFF) file.
 *
 * Pure Kotlin/JVM by design: no Android imports, so this class is unit-tested
 * off-device and used unchanged inside the app. The AudioRecord capture loop
 * feeds ShortArray chunks into [appendSamples]; [close] finalizes the RIFF
 * and data chunk sizes, which cannot be known until recording ends.
 *
 * Format is fixed to what whisper.cpp expects. Constants are exposed so tests
 * and the recorder assert against one source of truth.
 */
class WavWriter(private val file: File) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val BLOCK_ALIGN = CHANNELS * BITS_PER_SAMPLE / 8            // 2
        const val BYTE_RATE = SAMPLE_RATE * BLOCK_ALIGN                   // 32000
        const val HEADER_SIZE = 44
    }

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Long = 0
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(buildHeader(0))
    }

    /** Appends raw PCM samples. Little-endian, as WAV requires. */
    fun appendSamples(samples: ShortArray, count: Int = samples.size) {
        check(!closed) { "WavWriter is closed" }
        require(count in 0..samples.size) { "count out of range" }
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buf.putShort(samples[i])
        raf.write(buf.array())
        dataBytes += count * 2L
    }

    /** Total samples written so far. */
    fun sampleCount(): Long = dataBytes / 2

    /** Duration of audio written so far, in milliseconds. */
    fun durationMs(): Long = sampleCount() * 1000L / SAMPLE_RATE

    /** Finalizes chunk sizes in the header and closes the file. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        raf.seek(0)
        raf.write(buildHeader(dataBytes))
        raf.close()
    }

    private fun buildHeader(dataSize: Long): ByteArray {
        require(dataSize <= Int.MAX_VALUE - 36) { "WAV data too large" }
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36 + dataSize).toInt())          // RIFF chunk size
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)                                // fmt chunk size (PCM)
        b.putShort(1)                               // audio format: PCM
        b.putShort(CHANNELS.toShort())
        b.putInt(SAMPLE_RATE)
        b.putInt(BYTE_RATE)
        b.putShort(BLOCK_ALIGN.toShort())
        b.putShort(BITS_PER_SAMPLE.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataSize.toInt())                  // data chunk size
        return b.array()
    }
}
