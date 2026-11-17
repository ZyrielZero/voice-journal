package dev.zyriel.voicejournal.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record button's meter: silence is empty, loud is full, speech is in between. */
class InputLevelTest {

    private fun level(amplitude: Int, n: Int = 1600) =
        AudioRecorder.levelOf(ShortArray(n) { (if (it % 2 == 0) amplitude else -amplitude).toShort() }, n)

    @Test
    fun silenceAndAnEmptyReadAreZero() {
        assertEquals(0f, level(0), 0f)
        assertEquals(0f, AudioRecorder.levelOf(ShortArray(8) { 9000 }, 0), 0f)
    }

    @Test
    fun fullScaleFillsTheMeter() {
        assertEquals(1f, level(Short.MAX_VALUE.toInt()), 0f)
    }

    @Test
    fun louderReadsHigherAndSpeechSitsInsideTheRange() {
        val quiet = level(300)   // about -41 dBFS
        val speech = level(2000) // about -24 dBFS
        assertTrue(quiet > 0f && quiet < speech && speech < 1f)
    }

    @Test
    fun onlyTheSamplesReadAreCounted() {
        val chunk = ShortArray(1600) { if (it < 800) 0 else Short.MAX_VALUE }
        assertEquals(0f, AudioRecorder.levelOf(chunk, 800), 0f)
    }
}
