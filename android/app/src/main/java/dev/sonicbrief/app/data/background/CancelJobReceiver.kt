package dev.sonicbrief.app.data.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Backs the notification's Cancel action. Explicit intents only; never exported.
 */
@AndroidEntryPoint
class CancelJobReceiver : BroadcastReceiver() {
    @Inject
    lateinit var scheduler: JobScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_JOB) return
        val id = intent.getStringExtra(EXTRA_JOB_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                scheduler.cancel(id)
                ContextCompat.getSystemService(context, android.app.NotificationManager::class.java)
                    ?.cancel(NOTIFICATION_ID)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_JOB = "dev.sonicbrief.app.CANCEL_JOB"
        const val EXTRA_JOB_ID = "job_id"
        private const val NOTIFICATION_ID = 1401
    }
}
