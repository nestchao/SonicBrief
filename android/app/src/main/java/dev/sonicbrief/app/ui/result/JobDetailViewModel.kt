package dev.sonicbrief.app.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.Summary
import dev.sonicbrief.app.domain.model.Transcript
import dev.sonicbrief.app.domain.model.TranscriptSegment
import dev.sonicbrief.app.domain.model.VisualObservation
import dev.sonicbrief.app.domain.repository.ResultRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditableTranscriptSegment(
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val text: String,
)

data class JobDetailUiState(
    val job: Job? = null,
    val searchQuery: String = "",
    val currentMatch: Int = 0,
    val summaryTemplate: SummaryTemplate = SummaryTemplate.STANDARD,
    val summaryLanguageMode: SummaryLanguageMode = SummaryLanguageMode.SAME_AS_TRANSCRIPT,
    val loading: Boolean = false,
    val savingRevision: Boolean = false,
    val regeneratingSummary: Boolean = false,
    val transcripts: List<Transcript> = emptyList(),
    val summaries: List<Summary> = emptyList(),
    val visualObservations: List<VisualObservation> = emptyList(),
    val selectedTranscriptId: UUID? = null,
    val editableSegments: List<EditableTranscriptSegment> = emptyList(),
    val summaryLanguage: String = "en",
    val summaryInstructions: String = "",
    val error: String? = null,
    val message: String? = null,
) {
    enum class SummaryLanguageMode { AUTO, SAME_AS_TRANSCRIPT, CUSTOM }

    val selectedTranscript: Transcript?
        get() = transcripts.firstOrNull { it.id == selectedTranscriptId }

    val selectedSummaries: List<Summary>
        get() = summaries
            .filter { it.transcriptId == selectedTranscriptId }
            .sortedByDescending { it.createdAt }

    val hasUnsavedChanges: Boolean
        get() = editableSegments.any { it.text.trim() != it.originalText.trim() }

    val searchMatches: List<TranscriptMatch>
        get() = selectedTranscript?.findMatches(searchQuery).orEmpty()

    val canSaveRevision: Boolean
        get() = selectedTranscript != null &&
            editableSegments.isNotEmpty() &&
            editableSegments.all { it.text.isNotBlank() } &&
            hasUnsavedChanges &&
            !loading &&
            !savingRevision &&
            !regeneratingSummary
}

@HiltViewModel
class JobDetailViewModel @Inject constructor(
    private val repository: ResultRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(JobDetailUiState())
    val state: StateFlow<JobDetailUiState> = _state.asStateFlow()

    private var pendingRevisionRequestId: UUID? = null
    private var pendingSummaryRequestId: UUID? = null

    fun openJob(job: Job) {
        val current = _state.value.job
        if (current?.id == job.id && _state.value.transcripts.isNotEmpty()) {
            _state.update { it.copy(job = job) }
            return
        }

        pendingRevisionRequestId = null
        pendingSummaryRequestId = null
        _state.value = JobDetailUiState(
            job = job,
            loading = true,
            summaryLanguage = job.summaryLanguage,
            summaryInstructions = job.customInstructions.orEmpty(),
        )
        refresh()
    }

    fun refresh() {
        val job = _state.value.job ?: return
        if (_state.value.loading && _state.value.transcripts.isNotEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repository.fetch(job.id)
                .onSuccess { results ->
                    val previousSelection = _state.value.selectedTranscriptId
                    val selected = results.transcripts.firstOrNull { it.id == previousSelection }
                        ?: results.transcripts.maxByOrNull { it.revision }
                    val latestSummary = results.summaries
                        .filter { it.transcriptId == selected?.id }
                        .maxByOrNull { it.createdAt }
                    val preferences = parseSummaryInstructions(latestSummary?.customInstructions)

                    _state.update {
                        it.copy(
                            loading = false,
                            transcripts = results.transcripts.sortedByDescending(Transcript::revision),
                            summaries = results.summaries,
                            visualObservations = results.visualObservations,
                            selectedTranscriptId = selected?.id,
                            editableSegments = selected.toEditableSegments(),
                            summaryLanguage = latestSummary?.language ?: it.summaryLanguage,
                            summaryTemplate = preferences.template,
                            summaryInstructions = preferences.customInstructions,
                            error = null,
                        )
                    }
                }
                .onFailure { cause ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = cause.message ?: "Unable to load transcript results.",
                        )
                    }
                }
        }
    }

    fun selectTranscript(transcriptId: UUID) {
        val transcript = _state.value.transcripts.firstOrNull { it.id == transcriptId } ?: return
        val latestSummary = _state.value.summaries
            .filter { it.transcriptId == transcriptId }
            .maxByOrNull { it.createdAt }
        val preferences = parseSummaryInstructions(latestSummary?.customInstructions)
        pendingRevisionRequestId = null
        pendingSummaryRequestId = null
        _state.update {
            it.copy(
                selectedTranscriptId = transcript.id,
                editableSegments = transcript.toEditableSegments(),
                summaryLanguage = latestSummary?.language ?: it.summaryLanguage,
                summaryTemplate = preferences.template,
                summaryInstructions = preferences.customInstructions,
                error = null,
                message = null,
            )
        }
    }

    fun updateSegment(sequence: Int, value: String) {
        if (_state.value.savingRevision) return
        pendingRevisionRequestId = null
        pendingSummaryRequestId = null
        _state.update { state ->
            state.copy(
                editableSegments = state.editableSegments.map { segment ->
                    if (segment.sequence == sequence) segment.copy(text = value) else segment
                },
                error = null,
                message = null,
            )
        }
    }

    fun discardEdits() {
        val transcript = _state.value.selectedTranscript ?: return
        pendingRevisionRequestId = null
        pendingSummaryRequestId = null
        _state.update {
            it.copy(
                editableSegments = transcript.toEditableSegments(),
                message = "Unsaved transcript edits discarded.",
                error = null,
            )
        }
    }

    fun saveRevision() {
        val state = _state.value
        val transcript = state.selectedTranscript ?: return
        if (!state.canSaveRevision) return

        val requestId = pendingRevisionRequestId ?: UUID.randomUUID().also {
            pendingRevisionRequestId = it
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    savingRevision = true,
                    error = null,
                    message = null,
                )
            }

            repository.saveTranscriptRevision(
                baseTranscriptId = transcript.id,
                requestId = requestId,
                language = transcript.language,
                segments = state.editableSegments.map { it.sequence to it.text },
            ).onSuccess {
                pendingRevisionRequestId = null
                repository.fetch(transcript.jobId)
                    .onSuccess { results ->
                        val latest = results.transcripts.maxByOrNull { it.revision }
                        _state.update {
                            it.copy(
                                savingRevision = false,
                                loading = false,
                                transcripts = results.transcripts.sortedByDescending(Transcript::revision),
                                summaries = results.summaries,
                                visualObservations = results.visualObservations,
                                selectedTranscriptId = latest?.id,
                                editableSegments = latest.toEditableSegments(),
                                message = latest?.let { revision ->
                                    "Transcript revision ${revision.revision} saved."
                                } ?: "Transcript revision saved.",
                                error = null,
                            )
                        }
                    }
                    .onFailure { cause ->
                        _state.update {
                            it.copy(
                                savingRevision = false,
                                error = cause.message ?: "Revision saved, but results could not be refreshed.",
                            )
                        }
                    }
            }.onFailure { cause ->
                _state.update {
                    it.copy(
                        savingRevision = false,
                        error = cause.message ?: "Unable to save transcript revision.",
                    )
                }
            }
        }
    }

    fun updateSearchQuery(value: String) {
        _state.update { it.copy(searchQuery = value, currentMatch = 0, error = null) }
    }

    fun previousSearchMatch() {
        _state.update { state ->
            val count = state.searchMatches.size
            state.copy(currentMatch = moveMatchIndex(state.currentMatch, count, -1))
        }
    }

    fun nextSearchMatch() {
        _state.update { state ->
            val count = state.searchMatches.size
            state.copy(currentMatch = moveMatchIndex(state.currentMatch, count, 1))
        }
    }

    fun updateSummaryTemplate(value: SummaryTemplate) {
        _state.update { it.copy(summaryTemplate = value, error = null, message = null) }
        pendingSummaryRequestId = null
    }

    fun updateSummaryLanguageMode(value: JobDetailUiState.SummaryLanguageMode) {
        _state.update { it.copy(summaryLanguageMode = value, error = null, message = null) }
        pendingSummaryRequestId = null
    }

    fun updateSummaryLanguage(value: String) {
        pendingSummaryRequestId = null
        _state.update { it.copy(summaryLanguage = value, error = null, message = null) }
    }

    fun updateSummaryInstructions(value: String) {
        pendingSummaryRequestId = null
        _state.update { it.copy(summaryInstructions = value, error = null, message = null) }
    }

    fun regenerateSummary() {
        val state = _state.value
        val transcript = state.selectedTranscript ?: return
        val language = state.summaryLanguage.trim()
        if ((state.summaryLanguageMode == JobDetailUiState.SummaryLanguageMode.CUSTOM && language.isEmpty()) || state.regeneratingSummary || state.savingRevision || state.hasUnsavedChanges) {
            if (state.hasUnsavedChanges) {
                _state.update {
                    it.copy(error = "Save or discard transcript edits before regenerating the summary.")
                }
            }
            return
        }

        val requestId = pendingSummaryRequestId ?: UUID.randomUUID().also {
            pendingSummaryRequestId = it
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    regeneratingSummary = true,
                    error = null,
                    message = null,
                )
            }
            repository.regenerateSummary(
                transcriptId = transcript.id,
                requestId = requestId,
                language = when (state.summaryLanguageMode) {
                    JobDetailUiState.SummaryLanguageMode.CUSTOM -> language
                    JobDetailUiState.SummaryLanguageMode.AUTO,
                    JobDetailUiState.SummaryLanguageMode.SAME_AS_TRANSCRIPT -> transcript.language.ifBlank { "en" }
                },
                customInstructions = state.summaryTemplate.instructions(state.summaryInstructions),
            ).onSuccess {
                pendingSummaryRequestId = null
                repository.fetch(transcript.jobId)
                    .onSuccess { results ->
                        _state.update {
                            it.copy(
                                regeneratingSummary = false,
                                transcripts = results.transcripts.sortedByDescending(Transcript::revision),
                                summaries = results.summaries,
                                visualObservations = results.visualObservations,
                                message = "Summary regenerated from transcript revision ${transcript.revision}.",
                                error = null,
                            )
                        }
                    }
                    .onFailure { cause ->
                        _state.update {
                            it.copy(
                                regeneratingSummary = false,
                                error = cause.message ?: "Summary regenerated, but results could not be refreshed.",
                            )
                        }
                    }
            }.onFailure { cause ->
                _state.update {
                    it.copy(
                        regeneratingSummary = false,
                        error = cause.message ?: "Unable to regenerate summary.",
                    )
                }
            }
        }
    }

    fun clearFeedback() {
        _state.update { it.copy(error = null, message = null) }
    }
}

private fun Transcript?.toEditableSegments(): List<EditableTranscriptSegment> =
    this?.segments
        ?.sortedBy(TranscriptSegment::sequence)
        ?.map { segment ->
            EditableTranscriptSegment(
                sequence = segment.sequence,
                startMs = segment.startMs,
                endMs = segment.endMs,
                originalText = segment.text,
                text = segment.text,
            )
        }
        .orEmpty()
