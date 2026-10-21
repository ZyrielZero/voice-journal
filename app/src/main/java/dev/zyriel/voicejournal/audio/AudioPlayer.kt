package dev.zyriel.voicejournal.audio

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Plays back a recorded WAV file. One playback at a time; starting a new one
 * stops the previous. MediaPlayer handles WAV natively on all supported APIs.
 *
 * Not covered by the v0.4.x hardware robustness checklist; playback has no
 * documented device verification yet.
 */
class AudioPlayer {

    private val _playingFile = MutableStateFlow<File?>(null)
    val playingFile: StateFlow<File?> = _playingFile.asStateFlow()

    private var player: MediaPlayer? = null

    fun play(file: File) {
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
            mp.setOnPreparedListener { if (player === mp) mp.start() }
            mp.setDataSource(file.absolutePath)
            mp.prepareAsync()
            _playingFile.value = file
        } catch (e: Exception) {
            Log.w("AudioPlayer", "cannot play ${file.name}: ${e.message}")
            stop()
        }
    }

    fun stop() {
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        _playingFile.value = null
    }
}
