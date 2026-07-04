package dev.zyriel.voicejournal.audio

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Plays back a recorded WAV file. One playback at a time; starting a new one
 * stops the previous. MediaPlayer handles WAV natively on all supported APIs.
 *
 * UNVERIFIED ON HARDWARE.
 */
class AudioPlayer {

    private val _playingFile = MutableStateFlow<File?>(null)
    val playingFile: StateFlow<File?> = _playingFile.asStateFlow()

    private var player: MediaPlayer? = null

    fun play(file: File) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { this@AudioPlayer.stop() }
            setOnErrorListener { _, _, _ -> this@AudioPlayer.stop(); true }
            prepare()
            start()
        }
        _playingFile.value = file
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
