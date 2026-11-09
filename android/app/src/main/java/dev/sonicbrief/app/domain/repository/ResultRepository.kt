package dev.sonicbrief.app.domain.repository

import dev.sonicbrief.app.domain.model.Summary
import dev.sonicbrief.app.domain.model.Transcript
import dev.sonicbrief.app.domain.model.VisualObservation
import java.util.UUID

data class JobResults(
    val transcripts: List<Transcript>,
    val summaries: List<Summary>,
    val visualObservations: List<VisualObservation> = emptyList(),
)

interface ResultRepository {
    suspend fun fetch(jobId: UUID): Result<JobResults>

    suspend fun saveTranscriptRevision(
        baseTranscriptId: UUID,
        requestId: UUID,
        language: String?,
        segments: List<Pair<Int, String>>,
    ): Result<Unit>

    suspend fun regenerateSummary(
        transcriptId: UUID,
        requestId: UUID,
        language: String,
        customInstructions: String?,
    ): Result<Unit>
}
