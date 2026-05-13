package au.com.shiftyjelly.pocketcasts.voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import au.com.shiftyjelly.pocketcasts.voice.R
import au.com.shiftyjelly.pocketcasts.voice.ui.EnrollmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class VoiceControlNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_control_channel"
        private const val CHANNEL_NAME = "Voice Control"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Create the notification channel for voice control (required for Android O+).
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Voice control listening notification"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }

            notificationManager.createNotificationChannel(channel)
            Timber.i("Voice control notification channel created")
        }
    }

    /**
     * Create a notification indicating that voice control is actively listening.
     */
    fun createListeningNotification(): Notification {
        createNotificationChannel()

        val stopIntent = createStopIntent()
        val stopPendingIntent = PendingIntent.getService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Voice Control")
            .setContentText("Listening for voice commands")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent,
            )
            .build()
    }

    /**
     * Create a notification directing the user to enroll their voice.
     * Tapping opens the enrollment Activity.
     */
    fun createEnrollmentRequiredNotification(): Notification {
        createNotificationChannel()

        val enrollIntent = Intent(context, EnrollmentActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val enrollPendingIntent = PendingIntent.getActivity(
            context,
            0,
            enrollIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Voice Control")
            .setContentText("Enroll your voice to enable voice control")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(enrollPendingIntent)
            .build()
    }

    /**
     * Create an intent to stop the voice control service.
     */
    private fun createStopIntent(): Intent {
        return Intent(context, VoiceControlService::class.java).apply {
            action = "au.com.shiftyjelly.pocketcasts.voice.action.STOP"
        }
    }

    /**
     * Cancel the voice control notification.
     */
    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
        Timber.i("Voice control notification cancelled")
    }

    /**
     * Get the notification ID for the voice control service.
     */
    val notificationId: Int
        get() = NOTIFICATION_ID
}
