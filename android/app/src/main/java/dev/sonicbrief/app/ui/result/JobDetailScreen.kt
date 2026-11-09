package dev.sonicbrief.app.ui.result

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sonicbrief.app.domain.model.TranscriptSourceKind
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun JobDetailScreen(
    state: JobDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPreviousSearchMatch: () -> Unit,
    onNextSearchMatch: () -> Unit,
    onSelectTranscript: (UUID) -> Unit,
    onSegmentChange: (Int, String) -> Unit,
    onDiscardEdits: () -> Unit,
    onSaveRevision: () -> Unit,
    onSummaryLanguageChange: (String) -> Unit,
    onSummaryLanguageModeChange: (JobDetailUiState.SummaryLanguageMode) -> Unit,
    onSummaryTemplateChange: (SummaryTemplate) -> Unit,
    onSummaryInstructionsChange: (String) -> Unit,
    onRegenerateSummary: () -> Unit,
) {
    val job = state.job
    val selectedTranscript = state.selectedTranscript
    val latestSummary = state.selectedSummaries.firstOrNull()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun shareText(text: String, mime: String = "text/plain") {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share SonicBrief export"))
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Job result", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        job?.sourceFilename ?: job?.sourceUrl ?: "No job selected",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    job?.let {
                        Text(
                            "${it.status.name.lowercase().replaceFirstChar(Char::uppercase)} · ${it.processingProvider?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Pending provider"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.loading && !state.savingRevision && !state.regeneratingSummary,
                    ) {
                        Text("Refresh")
                    }
                }
            }
        }

        if (state.loading) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Loading transcript revisions and summaries…")
                }
            }
        }

        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        state.message?.let { message ->
            item {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (!state.loading && state.transcripts.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "No transcript is available for this job yet.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        if (state.transcripts.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search transcript") },
                    placeholder = { Text("Find text…") },
                    singleLine = true,
                )
                if (state.searchQuery.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.searchMatches.size} match${if (state.searchMatches.size == 1) "" else "es"}")
                        OutlinedButton(onClick = onPreviousSearchMatch, enabled = state.searchMatches.isNotEmpty()) { Text("Previous") }
                        OutlinedButton(onClick = onNextSearchMatch, enabled = state.searchMatches.isNotEmpty()) { Text("Next") }
                    }
                }
                Text("Search is case-insensitive and does not change the transcript.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("Transcript revision", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.transcripts.forEach { transcript ->
                        FilterChip(
                            selected = transcript.id == state.selectedTranscriptId,
                            onClick = { onSelectTranscript(transcript.id) },
                            enabled = !state.savingRevision && !state.regeneratingSummary,
                            label = {
                                Text(
                                    buildString {
                                        append("Revision ")
                                        append(transcript.revision)
                                        if (transcript.isUserEdited) append(" · edited")
                                    },
                                )
                            },
                        )
                    }
                }
                selectedTranscript?.let { transcript ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Source: " + transcriptSourceLabel(transcript.sourceKind),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${transcript.language} · ${transcript.segments.size} timestamped segment${if (transcript.segments.size == 1) "" else "s"} · ${transcript.createdAt.atZone(ZoneId.systemDefault()).format(resultDateFormatter)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Summary", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Summary generation uses the selected transcript revision only.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            item {
                Text("Summary template", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryTemplate.entries.forEach { template ->
                        FilterChip(selected = state.summaryTemplate == template, onClick = { onSummaryTemplateChange(template) }, label = { Text(template.label) })
                    }
                }
                Text("Summary language", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JobDetailUiState.SummaryLanguageMode.entries.forEach { mode ->
                        FilterChip(selected = state.summaryLanguageMode == mode, onClick = { onSummaryLanguageModeChange(mode) }, label = { Text(mode.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = state.summaryLanguage,
                    onValueChange = onSummaryLanguageChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Summary language") },
                    placeholder = { Text("en, zh-CN, ms") },
                    singleLine = true,
                    enabled = state.summaryLanguageMode == JobDetailUiState.SummaryLanguageMode.CUSTOM && !state.regeneratingSummary && !state.savingRevision,
                )
            }

            item {
                OutlinedTextField(
                    value = state.summaryInstructions,
                    onValueChange = onSummaryInstructionsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Custom summary instructions (optional)") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !state.regeneratingSummary && !state.savingRevision,
                )
            }

            item {
                Button(
                    onClick = onRegenerateSummary,
                    enabled = selectedTranscript != null &&
                        (state.summaryLanguageMode != JobDetailUiState.SummaryLanguageMode.CUSTOM || state.summaryLanguage.isNotBlank()) &&
                        !state.hasUnsavedChanges &&
                        !state.regeneratingSummary &&
                        !state.savingRevision,
                ) {
                    Text(if (state.regeneratingSummary) "Regenerating…" else "Regenerate summary")
                }
                if (state.hasUnsavedChanges) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Save or discard transcript edits before regenerating.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (latestSummary == null) {
                            Text("No summary exists for this transcript revision.")
                        } else {
                            Text(
                                "Latest summary",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${latestSummary.language} · ${latestSummary.model ?: "model not recorded"} · ${latestSummary.createdAt.atZone(ZoneId.systemDefault()).format(resultDateFormatter)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (state.selectedSummaries.size > 1) {
                                Text(
                                    "${state.selectedSummaries.size} summaries are preserved for this revision.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(latestSummary.content, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            item {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(latestSummary?.content.orEmpty())) }, enabled = latestSummary != null) { Text("Copy summary") }
                    OutlinedButton(onClick = { shareText(latestSummary?.content.orEmpty()) }, enabled = latestSummary != null) { Text("TXT") }
                    OutlinedButton(onClick = { shareText(latestSummary?.asMarkdown(state.job?.sourceFilename.orEmpty()).orEmpty(), "text/markdown") }, enabled = latestSummary != null) { Text("Markdown") }
                }
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Transcript editor", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Edit segment text while timestamps remain fixed. Saving creates a new revision and keeps the original.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(selectedTranscript?.asPlainText().orEmpty())) }) { Text("Copy transcript") }
                    OutlinedButton(onClick = { shareText(selectedTranscript?.asTimestampedText().orEmpty()) }) { Text("TXT") }
                    OutlinedButton(onClick = { shareText(selectedTranscript?.asMarkdown().orEmpty(), "text/markdown") }) { Text("Markdown") }
                    OutlinedButton(onClick = { shareText(selectedTranscript?.asSrt().orEmpty(), "application/x-subrip") }) { Text("SRT") }
                    OutlinedButton(onClick = { shareText(selectedTranscript?.asWebVtt().orEmpty(), "text/vtt") }) { Text("WebVTT") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSaveRevision,
                        enabled = state.canSaveRevision,
                    ) {
                        Text(if (state.savingRevision) "Saving…" else "Save as new revision")
                    }
                    OutlinedButton(
                        onClick = onDiscardEdits,
                        enabled = state.hasUnsavedChanges && !state.savingRevision,
                    ) {
                        Text("Discard edits")
                    }
                }
            }

            items(
                items = state.editableSegments,
                key = { it.sequence },
            ) { segment ->
                SegmentEditorCard(
                    segment = segment,
                    enabled = !state.savingRevision && !state.regeneratingSummary,
                    highlighted = state.searchMatches.any { it.segmentIndex == segment.sequence },
                    currentMatch = state.searchMatches.getOrNull(state.currentMatch)?.segmentIndex == segment.sequence,
                    onValueChange = { onSegmentChange(segment.sequence, it) },
                )
            }

            if (state.editableSegments.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSaveRevision,
                            enabled = state.canSaveRevision,
                        ) {
                            Text(if (state.savingRevision) "Saving…" else "Save as new revision")
                        }
                        OutlinedButton(
                            onClick = onDiscardEdits,
                            enabled = state.hasUnsavedChanges && !state.savingRevision,
                        ) {
                            Text("Discard edits")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SegmentEditorCard(
    segment: EditableTranscriptSegment,
    enabled: Boolean,
    highlighted: Boolean,
    currentMatch: Boolean,
    onValueChange: (String) -> Unit,
) {
    val changed = segment.text.trim() != segment.originalText.trim()

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (currentMatch) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else if (highlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "${formatTimestamp(segment.startMs)} – ${formatTimestamp(segment.endMs)} · segment ${segment.sequence + 1}${if (changed) " · edited" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = segment.text,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 8,
                enabled = enabled,
            )
        }
    }
}

private fun transcriptSourceLabel(kind: TranscriptSourceKind): String = when (kind) {
    TranscriptSourceKind.AUDIO -> "Audio transcription"
    TranscriptSourceKind.MANUAL_SUBTITLE -> "Manual subtitle"
    TranscriptSourceKind.AUTOMATIC_CAPTION -> "Automatic caption"
}

private fun formatTimestamp(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private val resultDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
