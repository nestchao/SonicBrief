package dev.sonicbrief.app.data.background

import androidx.work.Data
import androidx.work.workDataOf

enum class AcquisitionFailureKind {
    TRANSIENT_NETWORK,
    RATE_LIMIT,
    BOT_OR_AUTH_CHALLENGE,
    UNSUPPORTED_SOURCE,
    AUTH_SESSION_REQUIRED,
    STALE_RUNTIME,
    PROCESSING_FAILURE,
    CANCELLED,
    UNKNOWN,
}

/** Where the media for a job comes from. */
enum class JobSourceKind { URL, AUDIO_FILE, VIDEO_FILE }

object WorkContract {
    const val QUEUE_NAME = "sonicbrief-job-queue"
    const val TAG = "sonicbrief-job"
    const val INPUT_JOB_ID = "job_id"
    const val INPUT_SOURCE_KIND = "source_kind"
    const val INPUT_LOCAL_PATH = "local_path"
    const val MAX_ATTEMPTS = 3

    fun jobTag(jobId: String) = "$TAG-$jobId"

    fun input(jobId: String, kind: JobSourceKind, localPath: String?): Data = workDataOf(
        INPUT_JOB_ID to jobId,
        INPUT_SOURCE_KIND to kind.name,
        INPUT_LOCAL_PATH to localPath,
    )

    /** Progress bands that map each pipeline phase to the 0-100 bar (mirrors the desktop run_job). */
    object Bands {
        const val ACQUIRE_START = 4
        const val ACQUIRE_END = 24
        const val UPLOAD_END = 30
        const val TRANSCRIBE_END = 80
        const val SUMMARY = 85
    }
}
