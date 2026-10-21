package dev.zyriel.voicejournal.bench

import dev.zyriel.voicejournal.audio.WavReader
import dev.zyriel.voicejournal.audio.WavWriter
import java.io.File
import kotlin.random.Random

/**
 * Builds the pause-heavy clip for the VAD latency comparison from real
 * speech: each source WAV is followed by a pause as long as itself, so the
 * result is half speech, half pause. The pauses are near-silent room tone
 * (seeded noise around -63 dBFS) rather than digital zeros, because a real
 * journal pause is never exact silence and pure zeros flatter the VAD.
 *
 * This replaces the old 220 Hz tone version, which silero may not classify
 * as speech at all, so its VAD-on number could be trimming the "speech" too.
 * Pure JVM so the construction is unit-tested off-device.
 */
object GapsClip {

    /** Peak amplitude of the room-tone pauses, out of 32767 (about -63 dBFS). */
    const val ROOM_TONE_PEAK = 24

    /** Speech fraction of the built clip: each pause equals its segment. */
    const val SILENCE_RATIO = 0.5

    /**
     * Writes [sources] to [out] with a same-length pause after each one.
     * Returns the total sample count. Deterministic for a given [seed].
     */
    fun build(sources: List<File>, out: File, seed: Int = 7): Long {
        require(sources.isNotEmpty()) { "no source clips" }
        val rng = Random(seed)
        WavWriter(out).use { w ->
            for (src in sources) {
                val speech = WavReader.readFloatPcm(src)
                val pcm = ShortArray(speech.size) { i ->
                    (speech[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                }
                w.appendSamples(pcm)
                val pause = ShortArray(speech.size) {
                    rng.nextInt(-ROOM_TONE_PEAK, ROOM_TONE_PEAK + 1).toShort()
                }
                w.appendSamples(pause)
            }
            return w.sampleCount()
        }
    }
}
