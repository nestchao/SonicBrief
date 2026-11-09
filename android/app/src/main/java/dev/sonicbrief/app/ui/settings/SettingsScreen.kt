package dev.sonicbrief.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onKeyInputChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onTestKey: () -> Unit,
    onRemoveKey: () -> Unit,
    onModelInputChange: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onImportCookies: (android.net.Uri) -> Unit,
    onRemoveCookies: () -> Unit,
) {
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportCookies(uri)
    }
    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Gemini API key", style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.settings.hasApiKey) "Saved on this phone (ends in ${state.settings.apiKeyLast4 ?: "????"}). It is encrypted with the Android Keystore and only ever sent to Google."
            else "Get a key from Google AI Studio. It is stored encrypted on this phone and only sent to Google.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = onKeyInputChange,
            label = { Text(if (state.settings.hasApiKey) "Replace key" else "API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSaveKey, enabled = !state.busy && state.keyInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (state.busy) "Checking…" else "Validate & save key")
        }
        if (state.settings.hasApiKey) {
            OutlinedButton(onClick = onTestKey, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Test saved key") }
            OutlinedButton(onClick = onRemoveKey, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Remove key") }
        }

        HorizontalDivider()
        Text("Model", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.modelInput,
            onValueChange = onModelInputChange,
            label = { Text("Gemini model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.availableModels.take(12).forEach { name ->
                FilterChip(selected = state.modelInput == name, onClick = { onModelInputChange(name) }, label = { Text(name) })
            }
        }
        OutlinedButton(onClick = { onSaveModel(state.modelInput) }, modifier = Modifier.fillMaxWidth()) { Text("Use this model") }
        Text("Active model: ${state.settings.model}", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Cookies (optional)", style = MaterialTheme.typography.titleMedium)
        Text(
            "If YouTube asks you to confirm you're not a bot, import a cookies.txt exported from a browser where you're signed in. Only YouTube, Google and Bilibili cookies are kept, and they never leave this phone except in requests to those sites.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { cookiePicker.launch(arrayOf("text/plain", "*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.settings.cookiesImported) "Replace cookies.txt" else "Import cookies.txt")
        }
        if (state.settings.cookiesImported) {
            OutlinedButton(onClick = onRemoveCookies, modifier = Modifier.fillMaxWidth()) { Text("Remove cookies") }
        }

        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        HorizontalDivider()
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "SonicBrief for Android is free software (GPL-3.0). Downloads use yt-dlp via youtubedl-android. Your media is uploaded to Gemini with your own key for transcription and then deleted from Google's Files API.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
