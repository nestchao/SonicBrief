package dev.sonicbrief.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sonicbrief.app.data.background.JobScheduler
import dev.sonicbrief.app.data.job.JobStore
import dev.sonicbrief.app.data.settings.SettingsRepository
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.SourceType
import dev.sonicbrief.app.ui.result.SummaryTemplate
import dev.sonicbrief.app.ui.result.instructions
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val url: String = "",
    val pendingFileUri: Uri? = null,
    val pendingFileName: String? = null,
    val transcriptLanguage: String = "auto",
    val summaryLanguage: String = "zh-CN",
    val template: SummaryTemplate = SummaryTemplate.STANDARD,
    val instructions: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    val openJobId: UUID? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val scheduler: JobScheduler,
    private val store: JobStore,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    val jobs: StateFlow<List<Job>> = store.observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasApiKey: StateFlow<Boolean> = settings.observe()
        .map { it.hasApiKey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            val saved = settings.current()
            _state.update {
                it.copy(transcriptLanguage = saved.transcriptLanguage, summaryLanguage = saved.summaryLanguage)
            }
            scheduler.reconcile()
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value, error = null) }
    fun onTranscriptLanguageChange(value: String) = _state.update { it.copy(transcriptLanguage = value) }
    fun onSummaryLanguageChange(value: String) = _state.update { it.copy(summaryLanguage = value) }
    fun onTemplateChange(value: SummaryTemplate) = _state.update { it.copy(template = value) }
    fun onInstructionsChange(value: String) = _state.update { it.copy(instructions = value.take(4_000)) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun consumeOpenJob() = _state.update { it.copy(openJobId = null) }
    fun requestOpenJob(id: UUID) = _state.update { it.copy(openJobId = id) }

    /** Counts inbound shares so the UI can jump to Home when one arrives. */
    private val _shareRequests = MutableStateFlow(0)
    val shareRequests: StateFlow<Int> = _shareRequests.asStateFlow()

    /** Called for links shared from other apps. */
    fun prefillSharedText(text: String) {
        val link = URL_PATTERN.find(text)?.value ?: return
        _state.update { it.copy(url = link, pendingFileUri = null, pendingFileName = null, error = null) }
    }

    /** Called by the host activity after routing an ACTION_SEND intent here. */
    fun notifySharedContent() {
        _shareRequests.update { it + 1 }
    }

    fun setPendingFile(uri: Uri, name: String?) =
        _state.update { it.copy(pendingFileUri = uri, pendingFileName = name, url = "", error = null) }

    fun clearPendingFile() = _state.update { it.copy(pendingFileUri = null, pendingFileName = null) }

    fun start() {
        val current = _state.value
        if (current.submitting) return
        viewModelScope.launch {
            val currentSettings = settings.current()
            if (!currentSettings.hasApiKey) {
                _state.update { it.copy(error = "Add your Gemini API key in Settings first. It is required for summarization.") }
                return@launch
            }
            if (currentSettings.transcriptionProvider == "groq" && settings.groqApiKey().isNullOrBlank()) {
                _state.update { it.copy(error = "Add your Groq API key in Settings or switch the transcription provider to Gemini.") }
                return@launch
            }
            _state.update { it.copy(submitting = true, error = null) }
            settings.update { it.copy(transcriptLanguage = current.transcriptLanguage, summaryLanguage = current.summaryLanguage) }
            val options = JobScheduler.Options(
                transcriptLanguage = current.transcriptLanguage,
                summaryLanguage = current.summaryLanguage,
                instructions = current.template.instructions(current.instructions),
            )
            val result = when {
                current.pendingFileUri != null -> scheduler.submitFile(current.pendingFileUri, options)
                current.url.isNotBlank() -> scheduler.submitUrl(current.url, options)
                else -> Result.failure(IllegalArgumentException("Paste a YouTube/Bilibili link or pick a file."))
            }
            result
                .onSuccess {
                    _state.update { s -> s.copy(submitting = false, url = "", pendingFileUri = null, pendingFileName = null) }
                }
                .onFailure { cause ->
                    _state.update { it.copy(submitting = false, error = cause.message ?: "Unable to start the job.") }
                }
        }
    }

    fun cancel(jobId: UUID) = viewModelScope.launch { scheduler.cancel(jobId) }

    fun retry(job: Job) = viewModelScope.launch {
        scheduler.retryUrl(job).onFailure { cause -> _state.update { it.copy(error = cause.message) } }
    }

    fun rename(jobId: UUID, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) store.rename(jobId, title)
    }

    fun delete(job: Job) = viewModelScope.launch {
        if (job.status == dev.sonicbrief.app.domain.model.JobStatus.ACTIVE) scheduler.cancel(job.id)
        store.delete(job.id)
    }

    fun canRetry(job: Job) = job.sourceType == SourceType.URL

    private companion object {
        val URL_PATTERN = Regex("https?://[^\\s]+")
    }
}
