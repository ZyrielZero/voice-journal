package dev.zyriel.voicejournal.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
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
import java.io.IOException

/**
 * Captures microphone audio at 16 kHz mono 16-bit PCM and streams it to a
 * [WavWriter]. The capture loop runs on Dispatchers.IO; the UI observes
 * [elapsedMs] and never blocks.
 *
 * Hardware-verified (Pixel 10 Pro XL): capture including screen-off recording
 * passed the robustness checklist. Error paths (State.Error) are observed by
 * RecordingController, which salvages partial audio; a real mid-capture
 * AudioRecord failure has not been reproduced on hardware. The switch from
 * the MIC source to VOICE_RECOGNITION, and the disk-error path, are not yet
 * device-verified.
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

    @Volatile private var job: Job? = null

    /**
     * Starts recording into [outFile] and returns true once capture is
     * launched. Returns false on a synchronous failure (unsupported format,
     * permission missing, AudioRecord init failure, already recording), with
     * the reason in [State.Error] except for the already-recording case.
     *
     * Resets [state] to Idle on entry: a second identical failure must be a
     * fresh emission, and MutableStateFlow would swallow an equal Error.
     * Caller is responsible for having RECORD_AUDIO granted; a
     * SecurityException surfaces as [State.Error] rather than crashing.
     *
     * Uses the VOICE_RECOGNITION source, which asks the HAL for the
     * unprocessed-for-ASR path (no AGC or noise suppression tuned for calls).
     */
    @SuppressLint("MissingPermission") // permission is checked by the caller (UI layer)
    fun start(outFile: File): Boolean {
        if (_state.value is State.Recording) return false
        _state.value = State.Idle

        val minBuf = AudioRecord.getMinBufferSize(
            WavWriter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            _state.value = State.Error("16 kHz mono PCM16 unsupported on this device (minBuf=$minBuf)")
            return false
        }
        // 4x min buffer: headroom against underruns without meaningful latency cost.
        val bufSize = minBuf * 4

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WavWriter.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (e: SecurityException) {
            _state.value = State.Error("Microphone permission missing")
            return false
        } catch (e: IllegalArgumentException) {
            _state.value = State.Error("AudioRecord rejected the format: ${e.message}")
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            _state.value = State.Error("AudioRecord failed to initialize")
            return false
        }

        _state.value = State.Recording(outFile)
        _elapsedMs.value = 0L

        job = scope.launch(Dispatchers.IO) {
            val chunk = ShortArray(bufSize / 2)
            try {
                // An IOException from the writer (disk full, unwritable
                // filesDir) or from its closing header write used to escape
                // onto the controller scope and leave the pipeline in
                // Recording. Every capture failure now ends as State.Error,
                // which the controller observes and salvages.
                WavWriter(outFile).use { wav ->
                    recorder.startRecording()
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        _state.value = State.Error("Microphone is unavailable (in use by another app?)")
                        return@use
                    }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.value = State.Error("Could not write the recording: ${e.message}")
            } catch (e: IllegalStateException) {
                // startRecording() on a recorder the system revoked.
                _state.value = State.Error("Microphone failed to start: ${e.message}")
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                if (_state.value is State.Recording) _state.value = State.Idle
            }
        }
        return true
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
