package dev.zyriel.voicejournal.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Captures microphone audio at 16 kHz mono 16-bit PCM and streams it to a
 * [WavWriter]. The capture loop runs on Dispatchers.IO; the UI observes
 * [elapsedMs] and never blocks.
 *
 * UNVERIFIED ON HARDWARE. This class compiles against the Android SDK but has
 * not been executed on a device. AudioRecord behavior (buffer sizing, actual
 * sample rate support, permission timing) must be validated on a real phone
 * before step 1 is marked done.
 */
class AudioRecorder(private val scope: CoroutineScope) {

    sealed interface State {
        data object Idle : State
        data class Recording(val file: File) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private var job: Job? = null

    /**
     * Starts recording into [outFile]. Caller is responsible for having
     * RECORD_AUDIO granted before calling; a SecurityException surfaces as
     * [State.Error] rather than crashing.
     */
    @SuppressLint("MissingPermission") // permission is checked by the caller (UI layer)
    fun start(outFile: File) {
        if (_state.value is State.Recording) return

        val minBuf = AudioRecord.getMinBufferSize(
            WavWriter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            _state.value = State.Error("16 kHz mono PCM16 unsupported on this device (minBuf=$minBuf)")
            return
        }
        // 4x min buffer: headroom against underruns without meaningful latency cost.
        val bufSize = minBuf * 4

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WavWriter.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: SecurityException) {
            _state.value = State.Error("Microphone permission missing")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            _state.value = State.Error("AudioRecord failed to initialize")
            return
        }

        _state.value = State.Recording(outFile)
        _elapsedMs.value = 0L

        job = scope.launch(Dispatchers.IO) {
            val chunk = ShortArray(bufSize / 2)
            try {
                WavWriter(outFile).use { wav ->
                    recorder.startRecording()
                    while (isActive) {
                        val read = recorder.read(chunk, 0, chunk.size)
                        if (read > 0) {
                            wav.appendSamples(chunk, read)
                            _elapsedMs.value = wav.durationMs()
                        } else if (read < 0) {
                            _state.value = State.Error("AudioRecord.read error $read")
                            break
                        }
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                if (_state.value is State.Recording) _state.value = State.Idle
            }
        }
    }

    /** Stops recording. The WAV header is finalized by the capture loop's use{}. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Stops recording and suspends until the capture coroutine has fully
     * finished, guaranteeing the WAV header is finalized on disk. Use this
     * before handing the file to the transcriber; plain [stop] races it.
     */
    suspend fun stopAndWait() {
        job?.cancelAndJoin()
        job = null
    }
}
