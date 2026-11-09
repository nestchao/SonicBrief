package dev.sonicbrief.app.data.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.sonicbrief.app.data.gemini.GeminiException
import dev.sonicbrief.app.data.job.JobStore
import dev.sonicbrief.app.data.processing.PipelineConfigurationException
import dev.sonicbrief.app.data.processing.ProcessingPipeline
import dev.sonicbrief.app.domain.model.JobStage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

@HiltWorker
class ProcessingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pipeline: ProcessingPipeline,
    private val store: JobStore,
    private val classifier: AcquisitionFailureClassifier,
    private val notifier: JobNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifier.foregroundInfo("Starting", "Preparing", null)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(WorkContract.INPUT_JOB_ID)?.let(UUID::fromString)
            ?: return Result.failure()
        val kind = inputData.getString(WorkContract.INPUT_SOURCE_KIND)
            ?.let { runCatching { JobSourceKind.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        val localPath = inputData.getString(WorkContract.INPUT_LOCAL_PATH)

        return try {
            pipeline.run(jobId, kind, localPath) { stage, message, progress ->
                store.updateProgress(jobId, stage, progress, message)
                runCatching { setForeground(notifier.foregroundInfo(stageLabel(stage), message, progress)) }
            }
            pipeline.discardLocalSource(localPath)
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                pipeline.cancelActiveAcquisition()
                store.cancel(jobId)
                pipeline.discardLocalSource(localPath)
            }
            throw cancelled
        } catch (config: PipelineConfigurationException) {
            store.fail(jobId, "configuration", config.message ?: "Unable to start processing.")
            pipeline.discardLocalSource(localPath)
            Result.failure()
        } catch (rejected: GeminiException) {
            if (rejected.isKeyProblem()) {
                store.fail(
                    jobId, "gemini_key",
                    "Gemini rejected the API key or the model isn't available to it. Check the key and model in Settings.",
                )
                pipeline.discardLocalSource(localPath)
                Result.failure()
            } else {
                handleFailure(jobId, localPath, rejected)
            }
        } catch (failure: Throwable) {
            handleFailure(jobId, localPath, failure)
        }
    }

    private suspend fun handleFailure(jobId: UUID, localPath: String?, failure: Throwable): Result {
        return run {
            val kindOfFailure = classifier.classify(failure)
            if (classifier.retryable(kindOfFailure) && runAttemptCount < WorkContract.MAX_ATTEMPTS - 1) {
                store.updateProgress(jobId, JobStage.RETRYING, 0, "Retrying after a temporary problem")
                Result.retry()
            } else {
                store.fail(
                    jobId,
                    kindOfFailure.name.lowercase(),
                    classifier.userMessage(kindOfFailure, failure.message ?: "Processing failed."),
                )
                pipeline.discardLocalSource(localPath)
                Result.failure()
            }
        }
    }

    private fun GeminiException.isKeyProblem(): Boolean =
        statusCode == 401 || statusCode == 403 ||
            (statusCode == 400 && message.orEmpty().contains("api key", ignoreCase = true)) ||
            statusCode == 404

    private fun stageLabel(stage: JobStage): String = when (stage) {
        JobStage.VALIDATING -> "Checking link"
        JobStage.ACQUIRING_MEDIA -> "Getting audio"
        JobStage.UPLOADING -> "Uploading"
        JobStage.TRANSCRIBING -> "Transcribing"
        JobStage.SUMMARIZING -> "Summarizing"
        else -> "Working"
    }
}
