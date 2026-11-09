package dev.sonicbrief.app.ui.result

import dev.sonicbrief.app.domain.model.JobStage

/** The five user-facing phases of a transcription job, in order. */
enum class ProgressStep(val label: String) {
    VALIDATE("Check link"),
    DOWNLOAD("Download audio"),
    UPLOAD("Upload to Gemini"),
    TRANSCRIBE("Transcribe"),
    SUMMARIZE("Write summary"),
}

/**
 * Maps a pipeline stage to the step the user is currently on (0-based).
 * Returns -1 while the job is still queued behind other work.
 */
fun stepIndexFor(stage: JobStage): Int = when (stage) {
    JobStage.CREATED -> -1
    JobStage.VALIDATING -> 0
    JobStage.ACQUIRING_MEDIA -> 1
    JobStage.UPLOADING -> 2
    JobStage.TRANSCRIBING -> 3
    JobStage.SUMMARIZING -> 4
    JobStage.RETRYING -> -1
    JobStage.COMPLETED,
    JobStage.CANCELLED,
    JobStage.FAILED,
    -> 5
}

/** Short human clock for live progress: 0:07, 3:42, 1:05:09. */
fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
