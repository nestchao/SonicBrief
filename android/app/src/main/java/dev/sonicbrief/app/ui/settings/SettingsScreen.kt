package dev.sonicbrief.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonicbrief.app.ui.components.ChoicePill
import dev.sonicbrief.app.ui.components.PrimaryButton
import dev.sonicbrief.app.ui.components.ScreenHeader
import dev.sonicbrief.app.ui.components.SectionLabel
import dev.sonicbrief.app.ui.components.SecondaryButton
import dev.sonicbrief.app.ui.components.SoftCard
import dev.sonicbrief.app.ui.components.SoftDivider
import dev.sonicbrief.app.ui.components.SonicMark
import dev.sonicbrief.app.ui.components.SonicTextField
import dev.sonicbrief.app.ui.components.StatusPill
import dev.sonicbrief.app.ui.components.PillTone
import dev.sonicbrief.app.ui.icons.SonicIcons
import dev.sonicbrief.app.ui.theme.Indigo600

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onKeyInputChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onTestKey: () -> Unit,
    onRemoveKey: () -> Unit,
    onGroqKeyInputChange: (String) -> Unit,
    onSaveGroqKey: () -> Unit,
    onTestGroqKey: () -> Unit,
    onRemoveGroqKey: () -> Unit,
    onSaveProvider: (String) -> Unit,
    onSaveGroqModel: (String) -> Unit,
    onModelInputChange: (String) -> Unit,
    onSaveModel: (String) -> Unit,
    onImportCookies: (android.net.Uri) -> Unit,
    onRemoveCookies: () -> Unit,
) {
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportCookies(uri)
    }
    var keyVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = "Preferences",
                title = "Settings",
                subtitle = "Your key, model and optional helpers — all local to this phone.",
            )
        }

        state.error?.let { message ->
            item { InlineBanner(message, isError = true) }
        }
        state.message?.let { message ->
            item { InlineBanner(message, isError = false) }
        }

        item {
            SectionLabel("Gemini API key")
        }
        item {
            KeyCard(
                state = state,
                keyVisible = keyVisible,
                onToggleVisible = { keyVisible = !keyVisible },
                onKeyInputChange = onKeyInputChange,
                onSaveKey = onSaveKey,
                onTestKey = onTestKey,
                onRemoveKey = onRemoveKey,
            )
        }

        item {
            SectionLabel("Transcription provider")
        }
        item {
            GroqCard(
                state = state,
                onGroqKeyInputChange = onGroqKeyInputChange,
                onSaveGroqKey = onSaveGroqKey,
                onTestGroqKey = onTestGroqKey,
                onRemoveGroqKey = onRemoveGroqKey,
                onSaveProvider = onSaveProvider,
                onSaveGroqModel = onSaveGroqModel,
            )
        }

        item {
            SectionLabel("Gemini summarization model")
        }
        item {
            ModelCard(
                state = state,
                onModelInputChange = onModelInputChange,
                onSaveModel = onSaveModel,
            )
        }

        item {
            SectionLabel("Helpers")
        }
        item {
            CookiesCard(
                cookiesImported = state.settings.cookiesImported,
                busy = state.busy,
                onPick = { cookiePicker.launch(arrayOf("text/plain", "*/*")) },
                onRemove = onRemoveCookies,
            )
        }

        item {
            SectionLabel("About")
        }
        item {
            AboutCard()
        }
    }
}

@Composable
private fun GroqCard(
    state: SettingsUiState,
    onGroqKeyInputChange: (String) -> Unit,
    onSaveGroqKey: () -> Unit,
    onTestGroqKey: () -> Unit,
    onRemoveGroqKey: () -> Unit,
    onSaveProvider: (String) -> Unit,
    onSaveGroqModel: (String) -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }
    SoftCard {
        Text("Choose transcription provider", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill(label = "Gemini", selected = state.settings.transcriptionProvider == "gemini", onClick = { onSaveProvider("gemini") })
            ChoicePill(label = "Groq Whisper", selected = state.settings.transcriptionProvider == "groq", onClick = { onSaveProvider("groq") })
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Groq transcribes audio only. Gemini is still required for summaries. When Groq is selected, your audio is uploaded directly to Groq using your own API key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Groq API key", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (state.settings.hasGroqApiKey) "Encrypted on this phone · ends in ${state.settings.groqApiKeyLast4 ?: "••••"}"
                    else "Not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(if (state.settings.hasGroqApiKey) "Active" else "Missing",
                if (state.settings.hasGroqApiKey) PillTone.Success else PillTone.Error)
        }
        Spacer(Modifier.height(10.dp))
        SonicTextField(
            value = state.groqKeyInput,
            onValueChange = onGroqKeyInputChange,
            label = if (state.settings.hasGroqApiKey) "Replace Groq key" else "Paste Groq API key",
            placeholder = "gsk_…",
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { keyVisible = !keyVisible }) { Text(if (keyVisible) "Hide" else "Show") }
            },
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = if (state.busy) "Validating…" else "Validate & save Groq key",
            onClick = onSaveGroqKey,
            enabled = !state.busy && state.groqKeyInput.isNotBlank(),
            loading = state.busy,
            icon = Icons.Filled.Check,
        )
        if (state.settings.hasGroqApiKey) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Test", onTestGroqKey, enabled = !state.busy, fill = false, modifier = Modifier.weight(1f))
                SecondaryButton("Remove", onRemoveGroqKey, enabled = !state.busy, icon = Icons.Filled.Delete, fill = false, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Groq Whisper model", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill(
                label = "Large v3 Turbo",
                selected = state.settings.groqModel == "whisper-large-v3-turbo",
                onClick = { onSaveGroqModel("whisper-large-v3-turbo") },
            )
            ChoicePill(
                label = "Large v3",
                selected = state.settings.groqModel == "whisper-large-v3",
                onClick = { onSaveGroqModel("whisper-large-v3") },
            )
        }
    }
}

@Composable
private fun KeyCard(
    state: SettingsUiState,
    keyVisible: Boolean,
    onToggleVisible: () -> Unit,
    onKeyInputChange: (String) -> Unit,
    onSaveKey: () -> Unit,
    onTestKey: () -> Unit,
    onRemoveKey: () -> Unit,
) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SonicIcons.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("API key", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (state.settings.hasApiKey) "Ends in ${state.settings.apiKeyLast4 ?: "••••"}"
                    else "Not set yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.settings.hasApiKey) {
                StatusPill("Active", PillTone.Success)
            } else {
                StatusPill("Missing", PillTone.Error)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            if (state.settings.hasApiKey) {
                "Encrypted with the Android Keystore. It is only sent to Google."
            } else {
                "Create a key in Google AI Studio, then paste it here. It stays encrypted on this phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        SonicTextField(
            value = state.keyInput,
            onValueChange = onKeyInputChange,
            label = if (state.settings.hasApiKey) "Replace key" else "Paste API key",
            placeholder = "AIza…",
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = onToggleVisible) {
                    Text(
                        if (keyVisible) "Hide" else "Show",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
        )

        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            text = if (state.busy) "Checking…" else "Validate & save",
            onClick = onSaveKey,
            enabled = !state.busy && state.keyInput.isNotBlank(),
            loading = state.busy,
            icon = Icons.Filled.Check,
        )

        if (state.settings.hasApiKey) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "Test",
                    onClick = onTestKey,
                    enabled = !state.busy,
                    icon = SonicIcons.Bolt,
                    fill = false,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Remove",
                    onClick = onRemoveKey,
                    enabled = !state.busy,
                    icon = Icons.Filled.Delete,
                    fill = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    state: SettingsUiState,
    onModelInputChange: (String) -> Unit,
    onSaveModel: (String) -> Unit,
) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SonicIcons.Sparkle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Transcription model", style = MaterialTheme.typography.titleSmall)
                Text(
                    state.settings.model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SonicTextField(
            value = state.modelInput,
            onValueChange = onModelInputChange,
            label = "Model name",
            placeholder = "gemini-flash-lite-latest",
            singleLine = true,
        )

        if (state.availableModels.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Available for your key",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.availableModels.take(12).forEach { name ->
                    ChoicePill(
                        label = name.removePrefix("models/"),
                        selected = state.modelInput.removePrefix("models/") == name.removePrefix("models/"),
                        onClick = { onModelInputChange(name) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SecondaryButton(
            text = "Use this model",
            onClick = { onSaveModel(state.modelInput) },
            icon = Icons.Filled.Check,
        )
    }
}

@Composable
private fun CookiesCard(
    cookiesImported: Boolean,
    busy: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SonicIcons.Cookie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Browser cookies", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (cookiesImported) "Imported — bot checks handled" else "Optional",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (cookiesImported) StatusPill("On", PillTone.Success)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "If YouTube asks you to prove you're not a bot, import a cookies.txt exported from a browser where you're signed in. Only YouTube, Google and Bilibili cookies are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        SecondaryButton(
            text = if (cookiesImported) "Replace cookies.txt" else "Import cookies.txt",
            onClick = onPick,
            enabled = !busy,
            icon = SonicIcons.UploadFile,
        )
        if (cookiesImported) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = "Remove cookies",
                onClick = onRemove,
                icon = Icons.Filled.Delete,
            )
        }
    }
}

@Composable
private fun AboutCard() {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SonicMark(size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("SonicBrief for Android", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Free software, GPL-3.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        SoftDivider()
        Spacer(Modifier.height(14.dp))
        Text(
            "Media is downloaded with yt-dlp, uploaded to Gemini with your own key, transcribed, and then deleted from Google's Files API. Nothing is sent anywhere else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InlineBanner(message: String, isError: Boolean) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isError) Icons.Filled.Warning else Icons.Filled.Info,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = content)
    }
}
