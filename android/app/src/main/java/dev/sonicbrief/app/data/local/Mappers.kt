package dev.sonicbrief.app.data.local

import dev.sonicbrief.app.data.local.entity.JobEntity
import dev.sonicbrief.app.data.local.entity.SummaryEntity
import dev.sonicbrief.app.data.local.entity.TranscriptEntity
import dev.sonicbrief.app.data.local.entity.TranscriptSegmentEntity
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.JobStage
import dev.sonicbrief.app.domain.model.JobStatus
import dev.sonicbrief.app.domain.model.ProcessingMode
import dev.sonicbrief.app.domain.model.ProcessingProvider
import dev.sonicbrief.app.domain.model.SourceType
import dev.sonicbrief.app.domain.model.Summary
import dev.sonicbrief.app.domain.model.Transcript
import dev.sonicbrief.app.domain.model.TranscriptSegment
import dev.sonicbrief.app.domain.model.TranscriptSourceKind
import java.time.Instant
import java.util.UUID

private inline fun <reified E : Enum<E>> enumOr(value: String, default: E): E =
    enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default

fun JobEntity.toDomain(): Job = Job(
    id = UUID.fromString(id),
    sourceType = enumOr(sourceType, SourceType.URL),
    sourceUrl = sourceUrl,
    sourceFilename = sourceFilename,
    title = title,
    creator = creator,
    durationMs = durationMs,
    status = enumOr(status, JobStatus.FAILED),
    stage = enumOr(stage, JobStage.FAILED),
    progress = progress,
    stageDetail = stageDetail,
    processingMode = ProcessingMode.GEMINI,
    processingProvider = runCatching { ProcessingProvider.valueOf(provider.uppercase()) }
        .getOrDefault(ProcessingProvider.GEMINI),
    transcriptLanguage = transcriptLanguage,
    summaryLanguage = summaryLanguage,
    customInstructions = customInstructions,
    model = model,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAt = Instant.ofEpochMilli(createdAtMs),
    updatedAt = Instant.ofEpochMilli(updatedAtMs),
    completedAt = completedAtMs?.let(Instant::ofEpochMilli),
)

fun Job.toEntity(): JobEntity = JobEntity(
    id = id.toString(),
    sourceType = sourceType.name.lowercase(),
    sourceUrl = sourceUrl,
    sourceFilename = sourceFilename,
    title = title,
    creator = creator,
    durationMs = durationMs,
    status = status.name.lowercase(),
    stage = stage.name.lowercase(),
    progress = progress,
    stageDetail = stageDetail,
    transcriptLanguage = transcriptLanguage,
    summaryLanguage = summaryLanguage,
    customInstructions = customInstructions,
    provider = processingProvider?.name?.lowercase() ?: "gemini",
    model = model,
    errorCode = errorCode,
    errorMessage = errorMessage,
    createdAtMs = createdAt.toEpochMilli(),
    updatedAtMs = updatedAt.toEpochMilli(),
    completedAtMs = completedAt?.toEpochMilli(),
)

fun TranscriptEntity.toDomain(segments: List<TranscriptSegmentEntity>): Transcript = Transcript(
    id = UUID.fromString(id),
    jobId = UUID.fromString(jobId),
    language = language,
    revision = revision,
    fullText = fullText,
    segments = segments.map { TranscriptSegment(it.sequence, it.startMs, it.endMs, it.text) },
    sourceKind = enumOr(sourceKind, TranscriptSourceKind.AUDIO),
    isUserEdited = isUserEdited,
    createdAt = Instant.ofEpochMilli(createdAtMs),
)

fun SummaryEntity.toDomain(): Summary = Summary(
    id = UUID.fromString(id),
    jobId = UUID.fromString(jobId),
    transcriptId = UUID.fromString(transcriptId),
    transcriptRevision = transcriptRevision,
    language = language,
    content = content,
    model = model,
    customInstructions = customInstructions,
    createdAt = Instant.ofEpochMilli(createdAtMs),
)
