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

/** [Job.errorCode] used when the transcript is saved but the Gemini summary never landed. */
const val SUMMARY_FAILED_CODE = "summary_failed"

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

    /**
     * The transcript is saved but the summary never landed, so the job counts as completed
     * while a piece of the user's brief is missing. Cards must not show a plain "Ready".
     */
    val summaryFailed: Boolean
        get() = status == JobStatus.COMPLETED && errorCode == SUMMARY_FAILED_CODE

    /** One-line reason for cards and the detail banner, with a fallback when it is blank. */
    val summaryFailureMessage: String
        get() = errorMessage?.takeIf { it.isNotBlank() }
            ?: "Summary wasn't generated. Open the job to retry."
}

/**
 * A fresh summary means the job is done, even if an earlier attempt left it FAILED, CANCELLED,
 * or COMPLETED with a "summary failed" warning. Transcript + summary now exist, so the warning
 * badges on Library/Home cards must go away. Healthy jobs pass through untouched.
 */
fun Job.healedAfterSummary(now: Instant = Instant.now()): Job =
    if (status == JobStatus.FAILED || status == JobStatus.CANCELLED || summaryFailed) {
        copy(
            status = JobStatus.COMPLETED,
            stage = JobStage.COMPLETED,
            progress = 100,
            stageDetail = null,
            errorCode = null,
            errorMessage = null,
            completedAt = completedAt ?: now,
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
