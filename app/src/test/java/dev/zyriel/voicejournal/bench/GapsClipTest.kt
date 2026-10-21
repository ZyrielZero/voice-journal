package dev.zyriel.voicejournal.bench

import dev.zyriel.voicejournal.TestPaths
import dev.zyriel.voicejournal.audio.WavReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class GapsClipTest {

    @Test
    fun builtClipIsHalfSpeechHalfQuietRoomTone() {
        val sources = listOf("clip10s.wav", "clip30s.wav").map { TestPaths.debugAsset("accuracy/$it") }
        val out = File.createTempFile("gaps", ".wav")
        try {
            val total = GapsClip.build(sources, out)
            val speech = sources.map { WavReader.readFloatPcm(it) }
            assertEquals(2L * speech.sumOf { it.size }, total)

            val built = WavReader.readFloatPcm(out)
            assertEquals(total, built.size.toLong())

            // Speech is copied sample-exact, then a pause of the same length.
            val first = speech[0]
            assertArrayEquals(first, built.copyOfRange(0, first.size), 0f)
            val pause = built.copyOfRange(first.size, 2 * first.size)
            val peak = GapsClip.ROOM_TONE_PEAK / 32768f
            assertTrue("pause must be quiet room tone", pause.all { abs(it) <= peak + 1e-6f })
            assertTrue("pause must not be digital silence", pause.any { it != 0f })
        } finally {
            out.delete()
        }
    }

    @Test
    fun buildIsDeterministic() {
        val src = listOf(TestPaths.debugAsset("accuracy/clip10s.wav"))
        val a = File.createTempFile("gapsA", ".wav")
        val b = File.createTempFile("gapsB", ".wav")
        try {
            GapsClip.build(src, a)
            GapsClip.build(src, b)
            assertArrayEquals(a.readBytes(), b.readBytes())
        } finally {
            a.delete(); b.delete()
        }
    }
}
