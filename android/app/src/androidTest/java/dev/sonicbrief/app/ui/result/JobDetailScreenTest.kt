package dev.sonicbrief.app.ui.result

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.SourceType
import dev.sonicbrief.app.domain.model.Summary
import dev.sonicbrief.app.domain.model.Transcript
import dev.sonicbrief.app.domain.model.TranscriptSegment
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class JobDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun m11ResultControlsAreUsableWithFixtureData() {
        val jobId = UUID.randomUUID()
        val transcriptId = UUID.randomUUID()
        val transcript = Transcript(
            id = transcriptId,
            jobId = jobId,
            language = "zh-CN",
            revision = 1,
            fullText = "Hello hello 世界",
            segments = listOf(TranscriptSegment(0, 0, 1_000, "Hello hello 世界")),
        )
        var nextCount = 0
        var regenerated = 0
        var state by mutableStateOf(
            JobDetailUiState(
                job = Job(id = jobId, sourceType = SourceType.UPLOAD, sourceFilename = "fixture.wav"),
                transcripts = listOf(transcript),
                summaries = listOf(
                    Summary(UUID.randomUUID(), jobId, transcriptId, 1, "en", "Fixture summary"),
                ),
                selectedTranscriptId = transcriptId,
                editableSegments = listOf(EditableTranscriptSegment(0, 0, 1_000, "Hello hello 世界", "Hello hello 世界")),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                JobDetailScreen(
                    state = state,
                    onBack = {},
                    onRefresh = {},
                    onSearchQueryChange = { state = state.copy(searchQuery = it, currentMatch = 0) },
                    onPreviousSearchMatch = {},
                    onNextSearchMatch = { nextCount += 1 },
                    onSelectTranscript = {},
                    onSegmentChange = { _, _ -> },
                    onDiscardEdits = {},
                    onSaveRevision = {},
                    onSummaryLanguageChange = { state = state.copy(summaryLanguage = it) },
                    onSummaryLanguageModeChange = { state = state.copy(summaryLanguageMode = it) },
                    onSummaryTemplateChange = { state = state.copy(summaryTemplate = it) },
                    onSummaryInstructionsChange = { state = state.copy(summaryInstructions = it) },
                    onRegenerateSummary = { regenerated += 1 },
                )
            }
        }

        composeRule.onNodeWithText("fixture.wav").assertIsDisplayed()
        composeRule.onNodeWithText("Search transcript").performTextInput("HELLO")
        composeRule.onNodeWithText("2 matches").assertIsDisplayed()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.runOnIdle { assertEquals(1, nextCount) }

        composeRule.onNodeWithText("Key Points").performScrollTo().performClick()
        composeRule.onNodeWithText("Custom").performScrollTo().performClick()
        composeRule.onNodeWithText("Summary language").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Custom summary instructions (optional)").performScrollTo().performTextInput("Focus on decisions")
        composeRule.onNodeWithText("Regenerate summary").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(SummaryTemplate.KEY_POINTS, state.summaryTemplate)
            assertTextContainsSafe(state.summaryInstructions, "Focus on decisions")
            assertEquals(1, regenerated)
        }

        composeRule.onNodeWithText("Copy summary").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Copy transcript").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("TXT")[0].performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("SRT").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("WebVTT").performScrollTo().assertIsDisplayed()
    }

    private fun assertTextContainsSafe(actual: String, expected: String) {
        if (!actual.contains(expected)) throw AssertionError("Expected '$actual' to contain '$expected'")
    }
}
