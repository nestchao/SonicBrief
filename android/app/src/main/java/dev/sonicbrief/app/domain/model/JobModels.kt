package dev.sonicbrief.app.domain.model

import java.time.Instant
import java.util.UUID

enum class JobStatus {
    ACTIVE,
    COMPLETED,
    PAUSED,
    CANCELLED,
    FAILED,
}

enum class JobStage {
    CREATED,
    VALIDATING,
    ACQUIRING_MEDIA,
    UPLOADING,
    TRANSCRIBING,
    SUMMARIZING,
    RETRYING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

/** The standalone app only talks to Gemini; kept as an enum for forward compatibility. */
enum class ProcessingMode { GEMINI }

enum class ProcessingProvider { GEMINI, GROQ }

enum class SourceType {
    URL,
    UPLOAD,
}

enum class TranscriptSourceKind { AUDIO, MANUAL_SUBTITLE, AUTOMATIC_CAPTION }

data class Job(
    val id: UUID,
    val sourceType: SourceType,
    val sourceUrl: String? = null,
    val sourceFilename: String? = null,
    val title: String? = null,
    val creator: String? = null,
    val durationMs: Long? = null,
    val status: JobStatus = JobStatus.ACTIVE,
    val stage: JobStage = JobStage.CREATED,
    val progress: Int = 0,
    val stageDetail: String? = null,
    val processingMode: ProcessingMode = ProcessingMode.GEMINI,
    val processingProvider: ProcessingProvider? = null,
    /** Language hint for transcription: "auto", "en", "zh", ... */
    val transcriptLanguage: String = "auto",
    val summaryLanguage: String = "zh-CN",
    val customInstructions: String? = null,
    val model: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
) {
    /** A human label for lists: title, then filename, then URL. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: sourceFilename?.takeIf { it.isNotBlank() }
            ?: sourceUrl.orEmpty()
}

/**
 * A fresh summary means the job is done, even if an earlier pipeline stage (typically
 * summarization itself) left it FAILED. Transcript + summary now exist, so the FAILED
 * badge on Library/Home cards must go away. Jobs that already completed pass through.
 */
fun Job.healedAfterSummary(now: Instant = Instant.now()): Job =
    if (status == JobStatus.FAILED || status == JobStatus.CANCELLED) {
        copy(
            status = JobStatus.COMPLETED,
            stage = JobStage.COMPLETED,
            progress = 100,
            stageDetail = null,
            errorCode = null,
            errorMessage = null,
            completedAt = now,
        )
    } else {
        this
    }

data class TranscriptSegment(
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class Transcript(
    val id: UUID,
    val jobId: UUID,
    val language: String,
    val revision: Int,
    val fullText: String,
    val segments: List<TranscriptSegment>,
    val sourceKind: TranscriptSourceKind = TranscriptSourceKind.AUDIO,
    val isUserEdited: Boolean = false,
    val createdAt: Instant = Instant.now(),
)

data class Summary(
    val id: UUID,
    val jobId: UUID,
    val transcriptId: UUID,
    val transcriptRevision: Int,
    val language: String,
    val content: String,
    val model: String? = null,
    val customInstructions: String? = null,
    val createdAt: Instant = Instant.now(),
)

/** Kept so the shared result screen compiles; the standalone app never produces visual observations. */
data class VisualObservation(
    val sequence: Int,
    val startMs: Long,
    val endMs: Long? = null,
    val description: String,
    val visibleText: String? = null,
    val importance: Float? = null,
)
