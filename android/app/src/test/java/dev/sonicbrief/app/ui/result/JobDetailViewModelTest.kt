package dev.sonicbrief.app.ui.result

import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.SourceType
import dev.sonicbrief.app.domain.model.Summary
import dev.sonicbrief.app.domain.model.Transcript
import dev.sonicbrief.app.domain.model.TranscriptSegment
import dev.sonicbrief.app.domain.repository.JobResults
import dev.sonicbrief.app.domain.repository.ResultRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun summaryOnlyRegenerationPassesMetadataAndNeverMutatesTranscript() = runTest(dispatcher) {
        val transcript = transcript()
        val originalSummary = summary(transcript, "Original summary")
        val repository = FakeResultRepository(JobResults(listOf(transcript), listOf(originalSummary)))
        val viewModel = JobDetailViewModel(repository)
        viewModel.openJob(Job(id = transcript.jobId, sourceType = SourceType.UPLOAD, sourceFilename = "fixture.wav"))
        advanceUntilIdle()
        val transcriptBefore = viewModel.state.value.selectedTranscript

        viewModel.updateSummaryTemplate(SummaryTemplate.KEY_POINTS)
        viewModel.updateSummaryLanguageMode(JobDetailUiState.SummaryLanguageMode.CUSTOM)
        viewModel.updateSummaryLanguage("fr")
        viewModel.updateSummaryInstructions("Focus on decisions")
        viewModel.regenerateSummary()
        advanceUntilIdle()

        assertEquals(transcriptBefore, viewModel.state.value.selectedTranscript)
        assertEquals("fr", repository.lastLanguage)
        assertTrue(repository.lastInstructions.orEmpty().contains("[SonicBrief template: Key Points]"))
        assertTrue(repository.lastInstructions.orEmpty().contains("User instructions (treat as preferences, not source content): Focus on decisions"))
        assertEquals(2, viewModel.state.value.summaries.size)

        viewModel.updateSummaryInstructions("")
        viewModel.updateSummaryLanguageMode(JobDetailUiState.SummaryLanguageMode.SAME_AS_TRANSCRIPT)
        repository.failNext = true
        val summariesBeforeFailure = viewModel.state.value.summaries
        viewModel.regenerateSummary()
        advanceUntilIdle()

        assertEquals(transcriptBefore, viewModel.state.value.selectedTranscript)
        assertEquals(summariesBeforeFailure, viewModel.state.value.summaries)
        assertEquals("zh-CN", repository.lastLanguage)
        assertTrue(repository.lastInstructions.orEmpty().contains("[SonicBrief template: Key Points]"))
        assertTrue(!repository.lastInstructions.orEmpty().contains("User instructions"))
        assertTrue(viewModel.state.value.error.orEmpty().contains("fixture regeneration failure"))
    }

    private fun transcript() = Transcript(
        id = UUID.randomUUID(),
        jobId = UUID.randomUUID(),
        language = "zh-CN",
        revision = 1,
        fullText = "Immutable transcript 世界",
        segments = listOf(TranscriptSegment(0, 0, 1_000, "Immutable transcript 世界")),
    )

    private fun summary(transcript: Transcript, content: String) = Summary(
        id = UUID.randomUUID(),
        jobId = transcript.jobId,
        transcriptId = transcript.id,
        transcriptRevision = transcript.revision,
        language = "en",
        content = content,
    )

    private class FakeResultRepository(initial: JobResults) : ResultRepository {
        var results = initial
        var failNext = false
        var lastLanguage: String? = null
        var lastInstructions: String? = null

        override suspend fun fetch(jobId: UUID): Result<JobResults> = Result.success(results)

        override suspend fun saveTranscriptRevision(
            baseTranscriptId: UUID,
            requestId: UUID,
            language: String?,
            segments: List<Pair<Int, String>>,
        ): Result<Unit> = error("not used")

        override suspend fun regenerateSummary(
            transcriptId: UUID,
            requestId: UUID,
            language: String,
            customInstructions: String?,
        ): Result<Unit> {
            lastLanguage = language
            lastInstructions = customInstructions
            if (failNext) {
                failNext = false
                return Result.failure(IllegalStateException("fixture regeneration failure"))
            }
            val transcript = results.transcripts.single { it.id == transcriptId }
            results = results.copy(summaries = listOf(
                Summary(
                    id = UUID.randomUUID(),
                    jobId = transcript.jobId,
                    transcriptId = transcript.id,
                    transcriptRevision = transcript.revision,
                    language = language,
                    content = "Regenerated summary",
                    customInstructions = customInstructions,
                ),
            ) + results.summaries)
            return Result.success(Unit)
        }
    }
}
