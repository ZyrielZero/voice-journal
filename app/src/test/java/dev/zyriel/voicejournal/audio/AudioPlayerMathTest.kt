package dev.zyriel.voicejournal.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** The parts of [AudioPlayer] that need no MediaPlayer. */
class AudioPlayerMathTest {

    @Test
    fun `duration comes from the file size`() {
        // 16 kHz mono 16-bit is 32,000 bytes a second behind a 44-byte header.
        assertEquals(1_000L, AudioPlayer.wavDurationMs(44L + 32_000L))
        assertEquals(60_355L, AudioPlayer.wavDurationMs(1_931_404L))
    }

    @Test
    fun `a missing, empty or header-only file has no duration`() {
        assertEquals(0L, AudioPlayer.wavDurationMs(0L))
        assertEquals(0L, AudioPlayer.wavDurationMs(10L))
        assertEquals(0L, AudioPlayer.wavDurationMs(44L))
    }

    @Test
    fun `the speed button steps through every speed and comes back to normal`() {
        var speed = 1f
        val seen = mutableListOf(speed)
        repeat(AudioPlayer.SPEEDS.size) { speed = AudioPlayer.nextSpeed(speed); seen += speed }
        assertEquals(listOf(1f, 1.25f, 1.5f, 2f, 0.75f, 1f), seen)
    }

    @Test
    fun `an unknown speed goes back to normal`() {
        assertEquals(1f, AudioPlayer.nextSpeed(3f))
    }
}
