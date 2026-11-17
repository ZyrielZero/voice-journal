package dev.zyriel.voicejournal.audio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Plays back a recorded WAV file. One playback at a time; starting a new one
 * stops the previous. MediaPlayer handles WAV natively on all supported APIs.
 * Supports pause, seeking and playback speed, and publishes the position a
 * few times a second while playing. Call from the main thread.
 *
 * Not covered by the v0.4.x hardware robustness checklist; playback has no
 * documented device verification yet.
 */
class AudioPlayer {

    /** What is loaded right now. [positionMs] is refreshed while playing. */
    data class Playback(
        val file: File,
        val positionMs: Long,
        val durationMs: Long,
        val paused: Boolean,
        val speed: Float,
    )

    private val _playingFile = MutableStateFlow<File?>(null)

    /** The loaded file, playing or paused; null when nothing is loaded. */
    val playingFile: StateFlow<File?> = _playingFile.asStateFlow()

    private val _playback = MutableStateFlow<Playback?>(null)
    val playback: StateFlow<Playback?> = _playback.asStateFlow()

    private var player: MediaPlayer? = null
    private var prepared = false

    /** Kept across files: someone who listens at 1.5x wants the next entry at 1.5x. */
    private var speed = 1f

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val tick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            val state = _playback.value ?: return
            if (prepared && !state.paused) {
                runCatching { _playback.value = state.copy(positionMs = mp.currentPosition.toLong()) }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Loads [file] and plays it from [startMs]. */
    fun play(file: File, startMs: Long = 0L) {
        stop()
        // Callers check existence, not validity. Import is a source of WAVs
        // this app didn't write, and a corrupt one used to crash here via
        // an unhandled IOException from setDataSource/prepare. A file that
        // won't play is a no-op with a log line, not a crash.
        //
        // The player is assigned before setDataSource so a throw there is
        // released by stop() instead of leaking a native player, and it
        // prepares asynchronously so decoding never blocks the main thread;
        // a prepare failure arrives through the error listener.
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setOnCompletionListener { if (player === mp) stop() }
            mp.setOnErrorListener { _, _, _ -> if (player === mp) stop(); true }
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                prepared = true
                val state = _playback.value ?: return@setOnPreparedListener
                // Seeks and pauses asked for while it was still preparing.
                if (state.positionMs > 0L) mp.seekTo(state.positionMs.toInt())
                _playback.value = state.copy(durationMs = mp.duration.toLong().coerceAtLeast(state.durationMs))
                if (!state.paused) startAtSpeed(mp)
            }
            mp.setDataSource(file.absolutePath)
            mp.prepareAsync()
            _playingFile.value = file
            _playback.value = Playback(
                file = file,
                positionMs = startMs.coerceAtLeast(0L),
                durationMs = wavDurationMs(file.length()),
                paused = false,
                speed = speed,
            )
            handler.postDelayed(tick, TICK_MS)
        } catch (e: Exception) {
            Log.w("AudioPlayer", "cannot play ${file.name}: ${e.message}")
            stop()
        }
    }

    fun pause() {
        val state = _playback.value ?: return
        if (state.paused) return
        if (prepared) runCatching { player?.pause() }
        _playback.value = state.copy(paused = true)
    }

    fun resume() {
        val state = _playback.value ?: return
        if (!state.paused) return
        _playback.value = state.copy(paused = false)
        if (prepared) player?.let(::startAtSpeed)
    }

    /** Moves to [positionMs], playing or paused. */
    fun seekTo(positionMs: Long) {
        val state = _playback.value ?: return
        val target = positionMs.coerceIn(0L, state.durationMs.coerceAtLeast(0L))
        if (prepared) runCatching { player?.seekTo(target.toInt()) }
        _playback.value = state.copy(positionMs = target)
    }

    /** Sets the speed for this playback and the ones after it. */
    fun setSpeed(newSpeed: Float) {
        speed = newSpeed
        val state = _playback.value ?: return
        _playback.value = state.copy(speed = newSpeed)
        // Setting playback params on a paused player would start it, so a
        // paused player picks the speed up when it resumes.
        if (prepared && !state.paused) player?.let(::applySpeed)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        prepared = false
        _playingFile.value = null
        _playback.value = null
    }

    private fun startAtSpeed(mp: MediaPlayer) {
        runCatching { mp.start() }
        if (speed != 1f) applySpeed(mp)
    }

    private fun applySpeed(mp: MediaPlayer) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
            .onFailure { Log.w("AudioPlayer", "cannot set speed $speed: ${it.message}") }
    }

    companion object {
        private const val TICK_MS = 200L

        /** The speeds the speed button steps through, in order. */
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)

        /** The speed after [current] in [SPEEDS]; an unknown speed goes back to normal. */
        fun nextSpeed(current: Float): Float {
            val i = SPEEDS.indexOf(current)
            return if (i < 0) SPEEDS.first() else SPEEDS[(i + 1) % SPEEDS.size]
        }

        /**
         * Length of one of this app's recordings from its file size alone
         * (16 kHz mono 16-bit behind a 44-byte header), so a list can show
         * durations without opening every file. 0 for anything too short.
         */
        fun wavDurationMs(fileLength: Long): Long =
            (fileLength - WavWriter.HEADER_SIZE).coerceAtLeast(0L) * 1000L / WavWriter.BYTE_RATE
    }
}
