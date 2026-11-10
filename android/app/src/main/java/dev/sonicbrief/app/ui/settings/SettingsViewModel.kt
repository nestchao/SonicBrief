package dev.sonicbrief.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sonicbrief.app.data.gemini.GeminiApi
import dev.sonicbrief.app.data.gemini.GeminiModels
import dev.sonicbrief.app.data.groq.GroqApi
import dev.sonicbrief.app.data.media.AndroidPublicMediaDownloader
import dev.sonicbrief.app.data.media.CookieImport
import dev.sonicbrief.app.data.settings.AppSettings
import dev.sonicbrief.app.data.settings.SettingsRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val keyInput: String = "",
    val groqKeyInput: String = "",
    val modelInput: String = "",
    val availableModels: List<String> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val api: GeminiApi,
    private val groqApi: GroqApi,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe().collect { saved ->
                _state.update { it.copy(settings = saved, modelInput = if (it.modelInput.isBlank()) saved.model else it.modelInput) }
            }
        }
    }

    fun onGroqKeyInputChange(value: String) = _state.update { it.copy(groqKeyInput = value, error = null, message = null) }
    fun saveGroqKey() {
        val key = _state.value.groqKeyInput.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                groqApi.validateKey(key)
                repository.saveGroqApiKey(key)
                _state.update { it.copy(busy = false, groqKeyInput = "", message = "Groq key validated and saved.") }
            } catch (failure: Throwable) {
                _state.update { it.copy(busy = false, error = "Groq key check failed: ${failure.message}") }
            }
        }
    }
    fun testGroqKey() {
        viewModelScope.launch {
            val key = repository.groqApiKey() ?: return@launch _state.update { it.copy(error = "No Groq key saved.") }
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                groqApi.validateKey(key)
                _state.update { it.copy(busy = false, message = "Groq key works.") }
            } catch (failure: Throwable) {
                _state.update { it.copy(busy = false, error = "Groq key check failed: ${failure.message}") }
            }
        }
    }
    fun removeGroqKey() {
        viewModelScope.launch {
            repository.saveGroqApiKey("")
            _state.update { it.copy(message = "Groq key removed.") }
        }
    }
    fun saveTranscriptionProvider(provider: String) {
        if (provider !in setOf("gemini", "groq")) return
        viewModelScope.launch {
            repository.update { it.copy(transcriptionProvider = provider) }
            _state.update { it.copy(message = "Transcription provider set to ${provider.replaceFirstChar(Char::uppercase)}.") }
        }
    }
    fun saveGroqModel(model: String) {
        if (model !in setOf("whisper-large-v3-turbo", "whisper-large-v3")) return
        viewModelScope.launch {
            repository.update { it.copy(groqModel = model) }
            _state.update { it.copy(message = "Groq model set to $model.") }
        }
    }

    fun onKeyInputChange(value: String) = _state.update { it.copy(keyInput = value, error = null, message = null) }
    fun onModelInputChange(value: String) = _state.update { it.copy(modelInput = value, error = null, message = null) }

    fun saveKey() {
        val key = _state.value.keyInput.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                val models = api.listModels(key) // validates the key
                repository.saveApiKey(key)
                _state.update { it.copy(busy = false, keyInput = "", availableModels = models, message = "Key saved (${models.size} models available).") }
            } catch (failure: Throwable) {
                _state.update { it.copy(busy = false, error = "Key check failed: ${failure.message}") }
            }
        }
    }

    fun testSavedKey() {
        viewModelScope.launch {
            val key = repository.apiKey() ?: return@launch _state.update { it.copy(error = "No key saved.") }
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                val models = api.listModels(key)
                _state.update { it.copy(busy = false, availableModels = models, message = "Key works — ${models.size} models available.") }
            } catch (failure: Throwable) {
                _state.update { it.copy(busy = false, error = "Key check failed: ${failure.message}") }
            }
        }
    }

    fun removeKey() {
        viewModelScope.launch {
            repository.saveApiKey("")
            _state.update { it.copy(message = "Key removed.", availableModels = emptyList()) }
        }
    }

    fun saveModel(value: String) {
        val model = value.trim().removePrefix("models/")
        if (!GeminiModels.isValidName(model)) {
            _state.update { it.copy(error = "Model names look like gemini-flash-lite-latest.") }
            return
        }
        viewModelScope.launch {
            repository.update { it.copy(model = model) }
            _state.update { it.copy(modelInput = model, message = "Model set to $model.", error = null) }
        }
    }

    fun importCookies(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(CookieImport.MAX_BYTES + 1) }
                        ?: error("Unable to open the file.")
                    require(bytes.size <= CookieImport.MAX_BYTES) { "cookies.txt must be under 1 MB." }
                    val filtered = CookieImport.filter(String(bytes, Charsets.UTF_8))
                        ?: error("No YouTube, Google or Bilibili cookies were found in that file.")
                    File(context.filesDir, AndroidPublicMediaDownloader.COOKIES_FILE_NAME).writeText(filtered)
                }
            }
            outcome
                .onSuccess {
                    repository.setCookiesImported(true)
                    _state.update { it.copy(busy = false, message = "Cookies imported (YouTube/Google/Bilibili only).") }
                }
                .onFailure { cause -> _state.update { it.copy(busy = false, error = cause.message) } }
        }
    }

    fun removeCookies() {
        viewModelScope.launch {
            File(context.filesDir, AndroidPublicMediaDownloader.COOKIES_FILE_NAME).delete()
            repository.setCookiesImported(false)
            _state.update { it.copy(message = "Cookies removed.") }
        }
    }
}
