package dev.sonicbrief.app.data.background

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sonicbrief.app.data.job.JobStore
import dev.sonicbrief.app.data.media.PublicMediaUrlValidator
import dev.sonicbrief.app.data.settings.SettingsRepository
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.JobStage
import dev.sonicbrief.app.domain.model.JobStatus
import dev.sonicbrief.app.domain.model.ProcessingProvider
import dev.sonicbrief.app.domain.model.SourceType
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Creates jobs and queues them on a single sequential WorkManager chain (yt-dlp allows one process at a time). */
@Singleton
class JobScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: JobStore,
    private val settings: SettingsRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    data class Options(
        val transcriptLanguage: String,
        val summaryLanguage: String,
        val instructions: String?,
        val provider: ProcessingProvider = ProcessingProvider.GEMINI,
    )

    suspend fun submitUrl(rawUrl: String, options: Options): Result<Job> = runCatching {
        val url = PublicMediaUrlValidator.validate(rawUrl).getOrThrow()
        val job = newJob(SourceType.URL, options).copy(sourceUrl = url.normalizedUrl)
        store.save(job)
        enqueue(job, JobSourceKind.URL, null)
        job
    }

    suspend fun submitFile(uri: Uri, options: Options): Result<Job> = runCatching {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri) ?: "media-${System.currentTimeMillis()}"
        val mime = resolver.getType(uri).orEmpty()
        val isVideo = mime.startsWith("video/") ||
            (!mime.startsWith("audio/") && name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS)

        val job = newJob(SourceType.UPLOAD, options).copy(sourceFilename = name, title = name.substringBeforeLast('.'))
        val dir = File(context.cacheDir, "uploads/${job.id}").apply { mkdirs() }
        val extension = name.substringAfterLast('.', "").lowercase().ifBlank { if (isVideo) "mp4" else "m4a" }
        val target = File(dir, "source.$extension")
        try {
            withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("Unable to open the selected file.")
            }
        } catch (failure: Throwable) {
            dir.deleteRecursively()
            throw failure
        }
        store.save(job)
        enqueue(job, if (isVideo) JobSourceKind.VIDEO_FILE else JobSourceKind.AUDIO_FILE, target.absolutePath)
        job
    }

    /** Re-runs a failed/cancelled URL job. Picked files are removed after a terminal state, so those must be re-added. */
    suspend fun retryUrl(job: Job): Result<Unit> = runCatching {
        require(job.sourceType == SourceType.URL && !job.sourceUrl.isNullOrBlank()) {
            "Local files can't be retried — pick the file again."
        }
        store.save(
            job.copy(
                status = JobStatus.ACTIVE, stage = JobStage.CREATED, progress = 0,
                stageDetail = null, errorCode = null, errorMessage = null, completedAt = null,
            ),
        )
        enqueue(job, JobSourceKind.URL, null)
    }

    suspend fun cancel(jobId: UUID) {
        workManager.cancelAllWorkByTag(WorkContract.jobTag(jobId.toString()))
        store.cancel(jobId)
    }

    /** Called on launch: active jobs with no live work (process death) are marked failed. */
    suspend fun reconcile() {
        val live = withContext(Dispatchers.IO) {
            workManager.getWorkInfosByTag(WorkContract.TAG).get()
                .filter { !it.state.isFinished }
                .flatMap { it.tags }
                .filter { it.startsWith("${WorkContract.TAG}-") }
                .mapNotNull { runCatching { UUID.fromString(it.removePrefix("${WorkContract.TAG}-")) }.getOrNull() }
                .toSet()
        }
        store.failInterrupted(live)
    }

    private suspend fun newJob(sourceType: SourceType, options: Options): Job {
        val current = settings.current()
        return Job(
            id = UUID.randomUUID(),
            sourceType = sourceType,
            transcriptLanguage = options.transcriptLanguage,
            summaryLanguage = options.summaryLanguage,
            customInstructions = options.instructions?.takeIf { it.isNotBlank() },
            processingProvider = options.provider,
            model = if (options.provider == ProcessingProvider.GROQ) current.groqModel else current.model,
            createdAt = Instant.now(),
        )
    }

    private fun enqueue(job: Job, kind: JobSourceKind, localPath: String?) {
        val request = OneTimeWorkRequestBuilder<ProcessingWorker>()
            .setInputData(WorkContract.input(job.id.toString(), kind, localPath))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WorkContract.TAG)
            .addTag(WorkContract.jobTag(job.id.toString()))
            .build()
        workManager.enqueueUniqueWork(WorkContract.QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts")
    }
}
