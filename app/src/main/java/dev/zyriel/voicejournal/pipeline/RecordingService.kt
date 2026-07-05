package dev.zyriel.voicejournal.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.zyriel.voicejournal.MainActivity
import dev.zyriel.voicejournal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and microphone-legal while a recording or its
 * transcription is in flight. Deliberately does no work itself: the pipeline
 * lives in [RecordingController], and this service exists so the OS lets it
 * run with the screen off. Started by the controller from a foreground
 * context (the Rec tap), which the while-in-use microphone type requires.
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
 * Hardware-verified (Pixel 10 Pro XL): screen-off recording, the Stop
 * notification action, and the notifications-denied fallback all passed the
 * robustness checklist.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = RecordingController.get(this)

        if (intent?.action == ACTION_STOP) {
            controller.stopAndTranscribe()
            return START_NOT_STICKY
        }

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(recording = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicejournal:pipeline")
                .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        }

        // Follow the pipeline: swap the notification when transcription starts,
        // stop the service when the pipeline settles.
        scope.launch {
            controller.pipeline.collect { state ->
                when (state) {
                    is RecordingController.PipelineState.Recording -> Unit
                    is RecordingController.PipelineState.Transcribing ->
                        notificationManager().notify(NOTIFICATION_ID, buildNotification(recording = false))
                    is RecordingController.PipelineState.Idle,
                    is RecordingController.PipelineState.Failed -> {
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(recording: Boolean): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(if (recording) "Recording journal entry" else "Transcribing…")
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

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.zyriel.voicejournal.action.STOP_RECORDING"
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2 h backstop against leaks
    }
}
