package dev.sonicbrief.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.JobStatus
import dev.sonicbrief.app.ui.components.ChoicePill
import dev.sonicbrief.app.ui.components.EmptyState
import dev.sonicbrief.app.ui.components.GradientProgress
import dev.sonicbrief.app.ui.components.PrimaryButton
import dev.sonicbrief.app.ui.components.SectionLabel
import dev.sonicbrief.app.ui.components.SecondaryButton
import dev.sonicbrief.app.ui.components.SoftCard
import dev.sonicbrief.app.ui.components.SonicTextField
import dev.sonicbrief.app.ui.components.StatusPill
import dev.sonicbrief.app.ui.components.pillLabel
import dev.sonicbrief.app.ui.components.pillTone
import dev.sonicbrief.app.ui.icons.SonicIcons
import dev.sonicbrief.app.ui.result.SummaryTemplate
import dev.sonicbrief.app.ui.theme.BrandGradient
import dev.sonicbrief.app.ui.theme.Indigo600

val TRANSCRIPT_LANGUAGES = listOf("auto" to "Auto", "en" to "English", "zh" to "中文", "ms" to "Malay", "ja" to "日本語", "ko" to "한국어", "id" to "Indonesia")
val SUMMARY_LANGUAGES = listOf("zh-CN" to "简体中文", "en" to "English", "zh-TW" to "繁體中文", "ms" to "Malay", "ja" to "日本語", "ko" to "한국어", "id" to "Indonesia")

@OptIn(ExperimentalLayoutApi::class)
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
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
            onPickFile(uri, name)
        }
    }
    val active = jobs.firstOrNull { it.status == JobStatus.ACTIVE }
    val recent = jobs.filter { it.status != JobStatus.ACTIVE }.take(3)
    var showOptions by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            TopBar(hasApiKey = hasApiKey, onOpenSettings = onOpenSettings)
        }

        if (!hasApiKey) {
            item {
                SetupCard(onOpenSettings = onOpenSettings)
            }
        }

        if (active != null) {
            item {
                ActiveJobCard(active = active, onCancel = { onCancel(active) })
            }
        }

        item {
            CaptureCard(
                state = state,
                onUrlChange = onUrlChange,
                onPickFile = { picker.launch(arrayOf("audio/*", "video/*")) },
                onClearFile = onClearFile,
                onStart = onStart,
                canStart = !state.submitting && (state.url.isNotBlank() || state.pendingFileUri != null),
            )
        }

        item {
            OptionsToggle(
                expanded = showOptions,
                template = state.template.label,
                summaryLanguage = SUMMARY_LANGUAGES.firstOrNull { it.first == state.summaryLanguage }?.second
                    ?: state.summaryLanguage,
                onToggle = { showOptions = !showOptions },
            )
        }

        item {
            AnimatedVisibility(
                visible = showOptions,
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 4 },
                exit = fadeOut(tween(120)),
            ) {
                OptionsCard(
                    state = state,
                    onTranscriptLanguageChange = onTranscriptLanguageChange,
                    onSummaryLanguageChange = onSummaryLanguageChange,
                    onTemplateChange = onTemplateChange,
                    onInstructionsChange = onInstructionsChange,
                )
            }
        }

        state.error?.let { message ->
            item { ErrorBanner(message) }
        }

        if (recent.isNotEmpty()) {
            item {
                SectionLabel(
                    "Recent",
                    trailing = {
                        Text(
                            "${recent.size} of ${jobs.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            items(recent, key = { it.id }) { job ->
                RecentJobRow(
                    job = job,
                    onClick = { onOpenJob(job) },
                    onRetry = { onRetry(job) },
                )
            }
        } else {
            item {
                EmptyState(
                    icon = SonicIcons.FileText,
                    title = "Nothing here yet",
                    body = "Paste a link or pick a file to create your first brief.",
                )
            }
        }
    }
}

@Composable
private fun TopBar(hasApiKey: Boolean, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            dev.sonicbrief.app.ui.components.SonicMark(size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("SonicBrief", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Audio, distilled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (hasApiKey) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error),
                )
                Text(
                    if (hasApiKey) "Connected" else "Set up",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SetupCard(onOpenSettings: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(
                Brush.linearGradient(
                    listOf(
                        Indigo600.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    ),
                ),
            )
            .border(1.dp, Indigo600.copy(alpha = 0.2f), MaterialTheme.shapes.large)
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Indigo600),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(SonicIcons.Key, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add your Gemini key", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Stored encrypted on this phone only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            SecondaryButton(
                text = "Open settings",
                onClick = onOpenSettings,
                icon = SonicIcons.Sliders,
                fill = true,
            )
        }
    }
}

@Composable
private fun ActiveJobCard(active: Job, onCancel: () -> Unit) {
    SoftCard(containerColor = MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(BrandGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(SonicIcons.Sparkle, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    active.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    active.stageDetail ?: active.stage.name.lowercase().replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${active.progress}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(14.dp))
        GradientProgress(progress = active.progress / 100f)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun CaptureCard(
    state: HomeUiState,
    onUrlChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onStart: () -> Unit,
    canStart: Boolean,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("New brief", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Paste a public link or drop in a file. Everything stays on your phone until it reaches Gemini.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        SonicTextField(
            value = state.url,
            onValueChange = onUrlChange,
            enabled = state.pendingFileUri == null && !state.submitting,
            label = "Media link",
            placeholder = "https://youtube.com/watch?v=…",
            leadingIcon = SonicIcons.Link,
        )

        Spacer(Modifier.height(12.dp))

        if (state.pendingFileUri != null) {
            FileChip(
                name = state.pendingFileName ?: "Selected file",
                onRemove = onClearFile,
            )
        } else {
            DropZone(onPickFile = onPickFile, enabled = !state.submitting)
        }

        Spacer(Modifier.height(18.dp))

        PrimaryButton(
            text = if (state.submitting) "Starting…" else "Transcribe & summarize",
            onClick = onStart,
            enabled = canStart,
            loading = state.submitting,
            icon = SonicIcons.Sparkle,
        )
    }
}

@Composable
private fun FileChip(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SonicIcons.FileText, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Ready to upload",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun DropZone(onPickFile: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(enabled = enabled, onClick = onPickFile)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SonicIcons.UploadFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Pick a local file", style = MaterialTheme.typography.titleSmall)
            Text(
                "MP3, M4A, WAV, MP4 and more",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Browse",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun OptionsToggle(
    expanded: Boolean,
    template: String,
    summaryLanguage: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            SonicIcons.Sliders,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Brief options", style = MaterialTheme.typography.titleSmall)
            Text(
                "$template · $summaryLanguage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (expanded) "Hide" else "Customize",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsCard(
    state: HomeUiState,
    onTranscriptLanguageChange: (String) -> Unit,
    onSummaryLanguageChange: (String) -> Unit,
    onTemplateChange: (SummaryTemplate) -> Unit,
    onInstructionsChange: (String) -> Unit,
) {
    SoftCard(containerColor = MaterialTheme.colorScheme.surface) {
        Text("Brief style", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SummaryTemplate.entries.forEach { template ->
                ChoicePill(
                    label = template.label,
                    selected = state.template == template,
                    onClick = { onTemplateChange(template) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Spoken language", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TRANSCRIPT_LANGUAGES.forEach { (code, label) ->
                ChoicePill(
                    label = label,
                    selected = state.transcriptLanguage == code,
                    onClick = { onTranscriptLanguageChange(code) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Summary language", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SUMMARY_LANGUAGES.forEach { (code, label) ->
                ChoicePill(
                    label = label,
                    selected = state.summaryLanguage == code,
                    onClick = { onSummaryLanguageChange(code) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SonicTextField(
            value = state.instructions,
            onValueChange = onInstructionsChange,
            label = "Custom instructions (optional)",
            placeholder = "Focus on the decisions and next steps…",
            singleLine = false,
            minLines = 3,
            supportingText = "${state.instructions.length} / 4000",
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun RecentJobRow(job: Job, onClick: () -> Unit, onRetry: () -> Unit) {
    SoftCard(onClick = onClick, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (job.status == JobStatus.FAILED) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (job.status == JobStatus.FAILED) Icons.Filled.Warning else SonicIcons.FileText,
                    contentDescription = null,
                    tint = if (job.status == JobStatus.FAILED) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    job.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(job.status.pillLabel(), job.status.pillTone())
                    job.creator?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (job.status == JobStatus.FAILED && job.errorMessage != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                job.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (job.sourceUrl != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Retry",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onRetry),
                )
            }
        }
    }
}
