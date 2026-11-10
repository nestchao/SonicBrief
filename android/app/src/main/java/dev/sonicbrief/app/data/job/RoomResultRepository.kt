package dev.sonicbrief.app.data.job

import dev.sonicbrief.app.data.gemini.GeminiSummarizer
import dev.sonicbrief.app.data.local.TranscriptDao
import dev.sonicbrief.app.data.local.toDomain
import dev.sonicbrief.app.data.settings.SettingsRepository
import dev.sonicbrief.app.domain.model.healedAfterSummary
import dev.sonicbrief.app.domain.repository.JobResults
import dev.sonicbrief.app.domain.repository.ResultRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomResultRepository @Inject constructor(
    private val transcriptDao: TranscriptDao,
    private val store: JobStore,
    private val settings: SettingsRepository,
    private val summarizer: GeminiSummarizer,
) : ResultRepository {

    override suspend fun fetch(jobId: UUID): Result<JobResults> = runCatching {
        val transcripts = transcriptDao.getTranscripts(jobId.toString()).map { entity ->
            entity.toDomain(transcriptDao.getSegments(entity.id))
        }
        val summaries = transcriptDao.getSummaries(jobId.toString()).map { it.toDomain() }
        JobResults(transcripts = transcripts, summaries = summaries)
    }

    override suspend fun saveTranscriptRevision(
        baseTranscriptId: UUID,
        requestId: UUID,
        language: String?,
        segments: List<Pair<Int, String>>,
    ): Result<Unit> = runCatching {
        val base = transcriptDao.getTranscript(baseTranscriptId.toString())
            ?: error("The transcript no longer exists.")
        val edited = segments.toMap()
        val rows = transcriptDao.getSegments(base.id).map { segment ->
            Triple(segment.startMs, segment.endMs, edited[segment.sequence]?.trim()?.takeIf { it.isNotEmpty() } ?: segment.text)
        }
        store.saveTranscript(
            jobId = UUID.fromString(base.jobId),
            language = language?.takeIf { it.isNotBlank() } ?: base.language,
            segments = rows,
            userEdited = true,
        )
    }

    override suspend fun regenerateSummary(
        transcriptId: UUID,
        requestId: UUID,
        language: String,
        customInstructions: String?,
    ): Result<Unit> = runCatching {
        val entity = transcriptDao.getTranscript(transcriptId.toString()) ?: error("The transcript no longer exists.")
        val transcript = entity.toDomain(transcriptDao.getSegments(entity.id))
        val apiKey = settings.apiKey() ?: error("Add your Gemini API key in Settings first.")
        val model = settings.current().model
        val text = summarizer.summarize(apiKey, model, transcript, language, customInstructions)
        store.saveSummary(
            jobId = transcript.jobId,
            transcriptId = transcript.id,
            transcriptRevision = transcript.revision,
            language = language,
            content = text,
            model = model,
            instructions = customInstructions,
        )
        // Transcript + summary now exist, so a FAILED badge left by an earlier
        // summarization attempt must go away on Library/Home cards.
        store.get(transcript.jobId)?.let { job ->
            val healed = job.healedAfterSummary()
            if (healed != job) store.save(healed)
        }
    }
}
