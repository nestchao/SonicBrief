package dev.sonicbrief.app.data.job

import dev.sonicbrief.app.data.local.JobDao
import dev.sonicbrief.app.data.local.TranscriptDao
import dev.sonicbrief.app.data.local.entity.SummaryEntity
import dev.sonicbrief.app.data.local.entity.TranscriptEntity
import dev.sonicbrief.app.data.local.entity.TranscriptSegmentEntity
import dev.sonicbrief.app.data.local.toDomain
import dev.sonicbrief.app.data.local.toEntity
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.JobStage
import dev.sonicbrief.app.domain.model.JobStatus
import dev.sonicbrief.app.domain.model.TranscriptSourceKind
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed job history; the single source of truth for the standalone app. */
@Singleton
class JobStore @Inject constructor(
    private val jobDao: JobDao,
    private val transcriptDao: TranscriptDao,
) {
    fun observeJobs(): Flow<List<Job>> = jobDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeJob(id: UUID): Flow<Job?> = jobDao.observeById(id.toString()).map { it?.toDomain() }

    suspend fun get(id: UUID): Job? = jobDao.getById(id.toString())?.toDomain()

    suspend fun save(job: Job) = jobDao.upsert(job.copy(updatedAt = Instant.now()).toEntity())

    suspend fun rename(id: UUID, title: String) =
        jobDao.rename(id.toString(), title.trim(), System.currentTimeMillis())

    suspend fun delete(id: UUID) = jobDao.delete(id.toString())

    suspend fun updateProgress(id: UUID, stage: JobStage, progress: Int, detail: String?) {
        val job = get(id) ?: return
        if (job.status != JobStatus.ACTIVE) return
        save(job.copy(stage = stage, progress = progress.coerceIn(0, 100), stageDetail = detail))
    }

    suspend fun fail(id: UUID, code: String?, message: String) {
        val job = get(id) ?: return
        save(
            job.copy(
                status = JobStatus.FAILED,
                stage = JobStage.FAILED,
                errorCode = code,
                errorMessage = message,
                stageDetail = null,
                completedAt = Instant.now(),
            ),
        )
    }

    suspend fun cancel(id: UUID) {
        val job = get(id) ?: return
        if (job.status != JobStatus.ACTIVE) return
        save(job.copy(status = JobStatus.CANCELLED, stage = JobStage.CANCELLED, stageDetail = null, completedAt = Instant.now()))
    }

    /** Marks jobs that were running when the process died as failed (retryable by the user). */
    suspend fun failInterrupted(activeIds: Set<UUID>) {
        jobDao.getActive().forEach { entity ->
            if (UUID.fromString(entity.id) !in activeIds) {
                fail(UUID.fromString(entity.id), "interrupted", "Processing was interrupted. Tap retry to run it again.")
            }
        }
    }

    suspend fun saveTranscript(
        jobId: UUID,
        language: String,
        segments: List<Triple<Long, Long, String>>,
        sourceKind: TranscriptSourceKind = TranscriptSourceKind.AUDIO,
        userEdited: Boolean = false,
    ): dev.sonicbrief.app.domain.model.Transcript {
        val id = UUID.randomUUID()
        val revision = transcriptDao.maxRevision(jobId.toString()) + 1
        val entity = TranscriptEntity(
            id = id.toString(),
            jobId = jobId.toString(),
            language = language,
            revision = revision,
            fullText = segments.joinToString(" ") { it.third.trim() },
            sourceKind = sourceKind.name.lowercase(),
            isUserEdited = userEdited,
            createdAtMs = System.currentTimeMillis(),
        )
        val rows = segments.mapIndexed { index, (start, end, text) ->
            TranscriptSegmentEntity(id.toString(), index, start, end, text)
        }
        transcriptDao.insertTranscriptWithSegments(entity, rows)
        return entity.toDomain(rows)
    }

    suspend fun saveSummary(
        jobId: UUID,
        transcriptId: UUID,
        transcriptRevision: Int,
        language: String,
        content: String,
        model: String?,
        instructions: String?,
    ) {
        transcriptDao.upsertSummary(
            SummaryEntity(
                id = UUID.randomUUID().toString(),
                jobId = jobId.toString(),
                transcriptId = transcriptId.toString(),
                transcriptRevision = transcriptRevision,
                language = language,
                content = content,
                model = model,
                customInstructions = instructions,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
    }
}
