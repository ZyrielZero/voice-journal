package dev.zyriel.voicejournal.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a WAV produced by [WavWriter] into normalized 32-bit float PCM,
 * which is the input format whisper.cpp expects. Pure Kotlin, JVM-testable.
 */
object WavReader {

    /** Returns samples in [-1.0, 1.0]. Throws on anything that isn't our canonical format. */
    fun readFloatPcm(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= WavWriter.HEADER_SIZE) { "File too small to be a WAV" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        require(ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") { "Not a RIFF/WAVE file" }
        require(buf.getShort(20).toInt() == 1) { "Not PCM" }
        require(buf.getShort(22).toInt() == 1) { "Not mono" }
        require(buf.getInt(24) == WavWriter.SAMPLE_RATE) { "Not 16 kHz" }
        require(buf.getShort(34).toInt() == 16) { "Not 16-bit" }

        val dataSize = buf.getInt(40)
        require(WavWriter.HEADER_SIZE + dataSize <= bytes.size) { "data chunk exceeds file size" }

        val out = FloatArray(dataSize / 2)
        var pos = WavWriter.HEADER_SIZE
        for (i in out.indices) {
            out[i] = buf.getShort(pos) / 32768f
            pos += 2
        }
        return out
    }

    private fun ascii(b: ByteArray, at: Int, len: Int) = String(b, at, len, Charsets.US_ASCII)
}
