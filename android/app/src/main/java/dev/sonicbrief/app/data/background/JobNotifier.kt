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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ongoing job notification: stage-aware title, step counter, elapsed time, determinate
 * progress bar, an expanded BigText view, tap-to-open the job, and a Cancel action.
 */
@Singleton
class JobNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun foregroundInfo(
        jobId: UUID,
        title: String,
        stageLabel: String,
        stepText: String,
        message: String?,
        elapsedText: String?,
        progress: Int?,
    ): ForegroundInfo {
        ensureChannel()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sonicbrief_notification)
            .setColor(BRAND_INDIGO)
            .setContentTitle(title.ifBlank { "Working on your brief" })
            .setContentText(listOfNotNull(stageLabel, message?.takeIf { it.isNotBlank() }).joinToString(" · "))
            .setSubText(listOfNotNull(stepText.takeIf { it.isNotBlank() }, elapsedText?.let { "$it elapsed" }).joinToString(" · "))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        buildList {
                            add(stageLabel)
                            message?.takeIf { it.isNotBlank() }?.let { add(it) }
                            elapsedText?.let { add("$it elapsed · $stepText") }
                        }.joinToString("\n"),
                    ),
            )
            .setContentIntent(openJobIntent(jobId))
            .addAction(actionCancel(jobId))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress != null && progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return ForegroundInfo(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun openJobIntent(jobId: UUID): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_JOB_ID, jobId.toString())
        }
        return PendingIntent.getActivity(
            context,
            jobId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionCancel(jobId: UUID): NotificationCompat.Action {
        val intent = Intent(context, CancelJobReceiver::class.java).apply {
            action = CancelJobReceiver.ACTION_CANCEL_JOB
            putExtra(CancelJobReceiver.EXTRA_JOB_ID, jobId.toString())
        }
        val pending = PendingIntent.getBroadcast(
            context,
            jobId.hashCode() + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_sonicbrief_notification,
            "Cancel",
            pending,
        ).build()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Brief progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live progress while SonicBrief downloads, transcribes and summarizes."
                    setShowBadge(false)
                },
            )
        }
        // Retire the legacy channel from earlier builds so only one SonicBrief entry remains.
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
    }

    private companion object {
        const val CHANNEL_ID = "sonicbrief-jobs"
        const val LEGACY_CHANNEL_ID = "sonicbrief-job-progress"
        const val NOTIFICATION_ID = 1401
        const val BRAND_INDIGO = 0xFF5B46FB.toInt()
    }
}
