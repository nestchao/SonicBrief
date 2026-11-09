package dev.sonicbrief.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.JobStatus
import dev.sonicbrief.app.ui.result.SummaryTemplate
import androidx.compose.foundation.layout.FlowRow

val TRANSCRIPT_LANGUAGES = listOf("auto" to "Auto", "en" to "English", "zh" to "中文", "ms" to "Malay", "ja" to "日本語", "ko" to "한국어", "id" to "Indonesia")
val SUMMARY_LANGUAGES = listOf("zh-CN" to "简体中文", "en" to "English", "zh-TW" to "繁體中文", "ms" to "Malay", "ja" to "日本語", "ko" to "한국어", "id" to "Indonesia")

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    jobs: List<Job>,
    hasApiKey: Boolean,
    onUrlChange: (String) -> Unit,
    onPickFile: (android.net.Uri, String?) -> Unit,
    onClearFile: () -> Unit,
    onTranscriptLanguageChange: (String) -> Unit,
    onSummaryLanguageChange: (String) -> Unit,
    onTemplateChange: (SummaryTemplate) -> Unit,
    onInstructionsChange: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: (Job) -> Unit,
    onRetry: (Job) -> Unit,
    onOpenJob: (Job) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            onPickFile(uri, name)
        }
    }
    val active = jobs.firstOrNull { it.status == JobStatus.ACTIVE }
    val recent = jobs.filter { it.status != JobStatus.ACTIVE }.take(3)

    Column(
        modifier = Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("SonicBrief", style = MaterialTheme.typography.headlineMedium)
        Text("Transcribe and summarize audio on your phone with Gemini.", style = MaterialTheme.typography.bodyMedium)

        if (!hasApiKey) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add your Gemini API key to get started.", style = MaterialTheme.typography.titleSmall)
                    Text("The key stays on this phone, encrypted with the Android Keystore.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
        }

        if (active != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(active.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(active.stageDetail ?: active.stage.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { active.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onCancel(active) }) { Text("Cancel") }
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            label = { Text("YouTube / Bilibili / public media link") },
            singleLine = true,
            enabled = state.pendingFileUri == null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.pendingFileUri != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("File: ${state.pendingFileName ?: "selected file"}", modifier = Modifier.weight(1f), maxLines = 2)
                TextButton(onClick = onClearFile) { Text("Remove") }
            }
        } else {
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Pick audio or video file")
            }
        }

        Text("Spoken language", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TRANSCRIPT_LANGUAGES.forEach { (code, label) ->
                FilterChip(selected = state.transcriptLanguage == code, onClick = { onTranscriptLanguageChange(code) }, label = { Text(label) })
            }
        }
        Text("Summary language", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUMMARY_LANGUAGES.forEach { (code, label) ->
                FilterChip(selected = state.summaryLanguage == code, onClick = { onSummaryLanguageChange(code) }, label = { Text(label) })
            }
        }
        Text("Summary style", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTemplate.entries.forEach { template ->
                FilterChip(selected = state.template == template, onClick = { onTemplateChange(template) }, label = { Text(template.label) })
            }
        }
        OutlinedTextField(
            value = state.instructions,
            onValueChange = onInstructionsChange,
            label = { Text("Custom instructions (optional)") },
            supportingText = { Text("${state.instructions.length}/4000") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Button(
            onClick = onStart,
            enabled = !state.submitting && (state.url.isNotBlank() || state.pendingFileUri != null),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.submitting) "Starting…" else "Transcribe & summarize") }

        if (recent.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.titleMedium)
            recent.forEach { job ->
                Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenJob(job) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(job.displayTitle, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                        Text(job.status.name.lowercase(), style = MaterialTheme.typography.bodySmall)
                        if (job.status == JobStatus.FAILED) {
                            job.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            if (job.sourceUrl != null) TextButton(onClick = { onRetry(job) }) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}
