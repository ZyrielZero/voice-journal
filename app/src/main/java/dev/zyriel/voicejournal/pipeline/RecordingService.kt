package dev.zyriel.voicejournal.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.zyriel.voicejournal.MainActivity
import dev.zyriel.voicejournal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and microphone-legal while a recording or its
 * transcription is in flight, and while the launch sweep recovers an orphan. Deliberately does no work itself: the pipeline
 * lives in [RecordingController], and this service exists so the OS lets it
 * run with the screen off. Started by the controller from a foreground
 * context (the Rec tap, or the launch sweep right after the app opens),
 * which the while-in-use microphone type requires.
 *
 * Wakelock rationale: AudioRecord holds a CPU wakelock internally while
 * capturing, but transcription runs for a few seconds after capture stops,
 * with no audio I/O keeping the CPU awake. The partial wakelock spans the
 * whole pipeline so a screen-off stop can't doze mid-whisper. Timeout is a
 * backstop against a leak, generous enough for any plausible entry.
 *
 * START_NOT_STICKY: if the system kills us, a restarted service could not
 * reacquire the microphone from the background anyway. The orphan sweep is
 * the recovery path, not a zombie service.
 *
 * Owns audio focus for the recording (contract C5): transient focus while
 * Recording, and a stop on permanent or transient loss such as a call.
 *
 * Hardware-verified (Pixel 10 Pro XL): screen-off recording, the Stop
 * notification action, and the notifications-denied fallback all passed the
 * robustness checklist. Audio focus and the recovery phase are not yet
 * device-verified.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var follower: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    /** What the notification currently shows. */
    private enum class Phase { Recording, Transcribing, Recovering }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = RecordingController.get(this)

        if (intent?.action == ACTION_STOP) {
            controller.stopAndTranscribe()
            return START_NOT_STICKY
        }

        createChannel()
        // Reachable only from a visible app, but the mic permission can be
        // revoked between the tap and here, and Android 12+ refuses a
        // background start; either throws. The pipeline keeps running
        // without the service, so give up on the service, not the work.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(phaseOf(controller.pipeline.value)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refused: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicejournal:pipeline")
                .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        }

        // Follow the pipeline and launch recovery: swap the notification as
        // the phase changes, hold audio focus while recording, and stop the
        // service when both settle. One follower per service instance; a
        // repeated start command reuses it.
        if (follower?.isActive != true) {
            follower = scope.launch {
                combine(controller.pipeline, controller.recovering) { p, r -> p to r }.collect { (state, recovering) ->
                    if (state is RecordingController.PipelineState.Recording) requestFocus(controller)
                    else abandonFocus()
                    when (state) {
                        is RecordingController.PipelineState.Recording,
                        is RecordingController.PipelineState.Transcribing ->
                            notificationManager().notify(NOTIFICATION_ID, buildNotification(phaseOf(state)))
                        is RecordingController.PipelineState.Idle,
                        is RecordingController.PipelineState.Failed -> {
                            if (recovering) {
                                notificationManager().notify(NOTIFICATION_ID, buildNotification(Phase.Recovering))
                            } else {
                                stopSelf()
                            }
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun phaseOf(state: RecordingController.PipelineState): Phase = when (state) {
        is RecordingController.PipelineState.Recording -> Phase.Recording
        is RecordingController.PipelineState.Transcribing -> Phase.Transcribing
        else -> Phase.Recovering
    }

    /**
     * Transient focus for the length of a recording, so music pauses and a
     * phone call or another recorder takes it back. On any loss except a
     * duck (a notification chime), stop and keep what was said before the
     * interruption: the alternative is recording the call's silence.
     */
    private fun requestFocus(controller: RecordingController) {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.i(TAG, "audio focus lost ($change), stopping the recording")
                        controller.stopAndTranscribe()
                    }
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        val granted = audioManager().requestAudioFocus(req)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // A call is probably active. Keep recording; the user decides.
            Log.w(TAG, "audio focus not granted ($granted)")
        }
        focusRequest = req
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager().abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun onDestroy() {
        abandonFocus()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(phase: Phase): android.app.Notification {
        val recording = phase == Phase.Recording
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(
                when (phase) {
                    Phase.Recording -> "Recording journal entry"
                    Phase.Transcribing -> "Transcribing…"
                    Phase.Recovering -> "Recovering an unsaved entry…"
                }
            )
            .setOngoing(true)
            .setContentIntent(openApp)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recording) {
            builder
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .addAction(
                    0, "Stop",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
        }
        return builder.build()
    }

    private fun createChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Recording",
                NotificationManager.IMPORTANCE_LOW, // no sound, no heads-up; it's a status, not an alert
            ).apply { description = "Shown while a journal entry is being recorded or transcribed" }
        )
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun audioManager() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.zyriel.voicejournal.action.STOP_RECORDING"
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2 h backstop against leaks
    }
}
