package dev.sonicbrief.app.data.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sonicbrief.app.MainActivity
import dev.sonicbrief.app.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JobNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun foregroundInfo(title: String, message: String, progress: Int?): ForegroundInfo {
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sonicbrief_notification)
            .setContentTitle("SonicBrief")
            .setContentText("$title · $message")
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress != null && progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return ForegroundInfo(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Transcription progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress for SonicBrief downloads, transcription and summaries."
                },
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "sonicbrief-job-progress"
        const val NOTIFICATION_ID = 1401
    }
}
