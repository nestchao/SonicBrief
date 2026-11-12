package dev.sonicbrief.app.ui.result

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import dev.sonicbrief.app.ui.theme.IosMotion
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonicbrief.app.domain.model.TranscriptSourceKind
import dev.sonicbrief.app.ui.components.ChoicePill
import dev.sonicbrief.app.ui.components.SectionLabel
import dev.sonicbrief.app.ui.components.SelectableMarkdownText
import dev.sonicbrief.app.ui.components.SoftCard
import dev.sonicbrief.app.ui.components.SoftDivider
import dev.sonicbrief.app.ui.components.SonicTextField
import dev.sonicbrief.app.ui.components.StatusPill
import dev.sonicbrief.app.ui.components.WarningNote
import dev.sonicbrief.app.ui.components.pillLabel
import dev.sonicbrief.app.ui.components.pillTone
import dev.sonicbrief.app.ui.icons.SonicIcons
import dev.sonicbrief.app.ui.theme.BrandGradient
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
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
    var section by rememberSaveable { mutableStateOf(DetailTab.SUMMARY) }

    fun shareText(text: String, mime: String = "text/plain") {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Share SonicBrief export",
            ),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            DetailHero(
                state = state,
                onBack = onBack,
                onRefresh = onRefresh,
            )
        }

        state.error?.let { message ->
            item { FeedbackRow(message, isError = true) }
        }
        state.message?.let { message ->
            item { FeedbackRow(message, isError = false) }
        }

        // A completed job whose summary never landed used to look identical to a healthy one.
        // Spell it out here, next to the regenerate controls, until a summary exists.
        if (job?.summaryFailed == true && latestSummary == null) {
            item {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    WarningNote(job.summaryFailureMessage)
                }
            }
        }

        if (state.loading && state.transcripts.isEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading results…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@LazyColumn
        }

        if (!state.loading && state.transcripts.isEmpty()) {
            item {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 32.dp)) {
                    SoftCard {
                        Text("No transcript yet", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This job has not produced a transcript. Try refreshing, or start the job again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        if (state.transcripts.isNotEmpty()) {
            item {
                SegmentedTabs(selected = section, onSelect = { section = it })
            }

            when (section) {
                DetailTab.SUMMARY -> {
                    item {
                        SummarySection(
                            state = state,
                            latestSummary = latestSummary,
                            onSummaryTemplateChange = onSummaryTemplateChange,
                            onSummaryLanguageModeChange = onSummaryLanguageModeChange,
                            onSummaryLanguageChange = onSummaryLanguageChange,
                            onSummaryInstructionsChange = onSummaryInstructionsChange,
                            onRegenerateSummary = onRegenerateSummary,
                            onCopy = { clipboard.setText(AnnotatedString(latestSummary?.content.orEmpty())) },
                            onShareTxt = { shareText(latestSummary?.content.orEmpty()) },
                            onShareMarkdown = {
                                shareText(
                                    latestSummary?.asMarkdown(job?.sourceFilename.orEmpty()).orEmpty(),
                                    "text/markdown",
                                )
                            },
                        )
                    }
                }

                DetailTab.TRANSCRIPT -> {
                    item {
                        TranscriptSection(
                            state = state,
                            selectedTranscript = selectedTranscript,
                            onSearchQueryChange = onSearchQueryChange,
                            onPreviousSearchMatch = onPreviousSearchMatch,
                            onNextSearchMatch = onNextSearchMatch,
                            onSelectTranscript = onSelectTranscript,
                            onDiscardEdits = onDiscardEdits,
                            onSaveRevision = onSaveRevision,
                            onCopy = { clipboard.setText(AnnotatedString(selectedTranscript?.asPlainText().orEmpty())) },
                            onShareTimestamped = { shareText(selectedTranscript?.asTimestampedText().orEmpty()) },
                            onShareMarkdown = {
                                shareText(selectedTranscript?.asMarkdown().orEmpty(), "text/markdown")
                            },
                            onShareSrt = { shareText(selectedTranscript?.asSrt().orEmpty(), "application/x-subrip") },
                            onShareVtt = { shareText(selectedTranscript?.asWebVtt().orEmpty(), "text/vtt") },
                        )
                    }

                    items(
                        items = state.editableSegments,
                        key = { it.sequence },
                    ) { segment ->
                        SegmentCard(
                            segment = segment,
                            enabled = !state.savingRevision && !state.regeneratingSummary,
                            highlighted = state.searchMatches.any { it.segmentIndex == segment.sequence },
                            currentMatch = state.searchMatches.getOrNull(state.currentMatch)?.segmentIndex == segment.sequence,
                            onValueChange = { onSegmentChange(segment.sequence, it) },
                        )
                    }

                    if (state.editableSegments.isNotEmpty()) {
                        item {
                            Box(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    StyledAction(
                                        text = if (state.savingRevision) "Saving…" else "Save revision",
                                        primary = true,
                                        enabled = state.canSaveRevision,
                                        onClick = onSaveRevision,
                                    )
                                    StyledAction(
                                        text = "Discard",
                                        primary = false,
                                        enabled = state.hasUnsavedChanges && !state.savingRevision,
                                        onClick = onDiscardEdits,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class DetailTab(val label: String) {
    SUMMARY("Summary"),
    TRANSCRIPT("Transcript"),
}

@Composable
private fun DetailHero(
    state: JobDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val job = state.job
    val shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .padding(start = 20.dp, end = 20.dp, bottom = 22.dp, top = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                        enabled = !state.loading && !state.savingRevision && !state.regeneratingSummary,
                        onClick = onRefresh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(19.dp))
            }
        }

        Spacer(Modifier.height(18.dp))

        if (job != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(job.status.pillLabel(), job.status.pillTone())
                Spacer(Modifier.width(8.dp))
                job.durationMs?.takeIf { it > 0 }?.let {
                    StatusPill(formatDuration(it), dev.sonicbrief.app.ui.components.PillTone.Neutral, leadingDot = false)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Text(
            job?.title?.takeIf { it.isNotBlank() } ?: job?.displayTitle.orEmpty().ifEmpty { "Job result" },
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (job != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                job.creator?.takeIf { it.isNotBlank() }
                    ?: job.sourceFilename
                    ?: job.sourceUrl.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            resultDateFormatter.format(job?.createdAt ?: java.time.Instant.EPOCH),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SegmentedTabs(selected: DetailTab, onSelect: (DetailTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DetailTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummarySection(
    state: JobDetailUiState,
    latestSummary: dev.sonicbrief.app.domain.model.Summary?,
    onSummaryTemplateChange: (SummaryTemplate) -> Unit,
    onSummaryLanguageModeChange: (JobDetailUiState.SummaryLanguageMode) -> Unit,
    onSummaryLanguageChange: (String) -> Unit,
    onSummaryInstructionsChange: (String) -> Unit,
    onRegenerateSummary: () -> Unit,
    onCopy: () -> Unit,
    onShareTxt: () -> Unit,
    onShareMarkdown: () -> Unit,
) {
    var showCustomize by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (latestSummary == null) {
            SoftCard {
                Text("No summary for this revision", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    state.job?.takeIf { it.summaryFailed }?.summaryFailureMessage
                        ?: "Generate a summary from the transcript below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SoftCard(contentPadding = 0.dp) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(BrandGradient),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(SonicIcons.Sparkle, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Summary", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${latestSummary.language} · revision ${latestSummary.transcriptRevision}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    SelectableMarkdownText(latestSummary.content)
                }
                SoftDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ExportAction("Copy", SonicIcons.Copy, onCopy, Modifier.weight(1f))
                    ExportAction("TXT", SonicIcons.FileText, onShareTxt, Modifier.weight(1f))
                    ExportAction("Markdown", SonicIcons.Download, onShareMarkdown, Modifier.weight(1f))
                }
            }

            if (state.selectedSummaries.size > 1) {
                Text(
                    "${state.selectedSummaries.size} summaries preserved for this revision.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SoftCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCustomize = !showCustomize },
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
                    Text("Customize summary", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.summaryTemplate.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (showCustomize) "Hide" else "Edit",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            AnimatedVisibility(
                visible = showCustomize,
                enter = IosMotion.expandEnter(),
                exit = IosMotion.collapseExit(),
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Text("Style", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SummaryTemplate.entries.forEach { template ->
                            ChoicePill(
                                label = template.label,
                                selected = state.summaryTemplate == template,
                                onClick = { onSummaryTemplateChange(template) },
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Language", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        JobDetailUiState.SummaryLanguageMode.entries.forEach { mode ->
                            ChoicePill(
                                label = when (mode) {
                                    JobDetailUiState.SummaryLanguageMode.AUTO -> "Auto"
                                    JobDetailUiState.SummaryLanguageMode.SAME_AS_TRANSCRIPT -> "Match transcript"
                                    JobDetailUiState.SummaryLanguageMode.CUSTOM -> "Custom"
                                },
                                selected = state.summaryLanguageMode == mode,
                                onClick = { onSummaryLanguageModeChange(mode) },
                            )
                        }
                    }

                    if (state.summaryLanguageMode == JobDetailUiState.SummaryLanguageMode.CUSTOM) {
                        Spacer(Modifier.height(12.dp))
                        SonicTextField(
                            value = state.summaryLanguage,
                            onValueChange = onSummaryLanguageChange,
                            label = "Language code",
                            placeholder = "en, zh-CN, ms",
                            enabled = !state.regeneratingSummary && !state.savingRevision,
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    SonicTextField(
                        value = state.summaryInstructions,
                        onValueChange = onSummaryInstructionsChange,
                        label = "Custom instructions",
                        placeholder = "Emphasize takeaways and open questions…",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                        enabled = !state.regeneratingSummary && !state.savingRevision,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            StyledAction(
                text = if (state.regeneratingSummary) "Regenerating…" else "Regenerate summary",
                primary = true,
                enabled = state.selectedTranscript != null &&
                    (state.summaryLanguageMode != JobDetailUiState.SummaryLanguageMode.CUSTOM || state.summaryLanguage.isNotBlank()) &&
                    !state.hasUnsavedChanges &&
                    !state.regeneratingSummary &&
                    !state.savingRevision,
                onClick = onRegenerateSummary,
            )
            if (state.hasUnsavedChanges) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Save or discard transcript edits first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptSection(
    state: JobDetailUiState,
    selectedTranscript: dev.sonicbrief.app.domain.model.Transcript?,
    onSearchQueryChange: (String) -> Unit,
    onPreviousSearchMatch: () -> Unit,
    onNextSearchMatch: () -> Unit,
    onSelectTranscript: (UUID) -> Unit,
    onDiscardEdits: () -> Unit,
    onSaveRevision: () -> Unit,
    onCopy: () -> Unit,
    onShareTimestamped: () -> Unit,
    onShareMarkdown: () -> Unit,
    onShareSrt: () -> Unit,
    onShareVtt: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SoftCard {
            SonicTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = "Search the transcript",
                leadingIcon = Icons.Filled.Search,
                trailingIcon = if (state.searchQuery.isNotBlank()) {
                    {
                        Text(
                            "${state.searchMatches.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                } else null,
            )
            if (state.searchQuery.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onPreviousSearchMatch, enabled = state.searchMatches.isNotEmpty()) {
                        Text("Previous")
                    }
                    TextButton(onClick = onNextSearchMatch, enabled = state.searchMatches.isNotEmpty()) {
                        Text("Next")
                    }
                }
            }
        }

        if (state.transcripts.size > 1) {
            SoftCard {
                SectionLabel("Revisions")
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.transcripts.forEach { transcript ->
                        ChoicePill(
                            label = "v${transcript.revision}" + if (transcript.isUserEdited) " · edited" else "",
                            selected = transcript.id == state.selectedTranscriptId,
                            enabled = !state.savingRevision && !state.regeneratingSummary,
                            onClick = { onSelectTranscript(transcript.id) },
                        )
                    }
                }
            }
        }

        selectedTranscript?.let { transcript ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(transcriptSourceLabel(transcript.sourceKind), dev.sonicbrief.app.ui.components.PillTone.Brand, leadingDot = false)
                StatusPill("${transcript.segments.size} segments", dev.sonicbrief.app.ui.components.PillTone.Neutral, leadingDot = false)
            }
        }

        SoftCard(contentPadding = 0.dp) {
            Column(Modifier.padding(12.dp)) {
                SectionLabel("Export")
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlineChip("Copy", SonicIcons.Copy, onCopy)
                    OutlineChip("TXT", SonicIcons.FileText, onShareTimestamped)
                    OutlineChip("Markdown", SonicIcons.Download, onShareMarkdown)
                    OutlineChip("SRT", SonicIcons.Timer, onShareSrt)
                    OutlineChip("WebVTT", SonicIcons.Timer, onShareVtt)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StyledAction(
                text = if (state.savingRevision) "Saving…" else "Save revision",
                primary = true,
                enabled = state.canSaveRevision,
                onClick = onSaveRevision,
            )
            StyledAction(
                text = "Discard",
                primary = false,
                enabled = state.hasUnsavedChanges && !state.savingRevision,
                onClick = onDiscardEdits,
            )
        }

        SectionLabel("Segments", trailing = {
            Text(
                "Edit text — timestamps stay fixed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        })
    }
}

@Composable
private fun SegmentCard(
    segment: EditableTranscriptSegment,
    enabled: Boolean,
    highlighted: Boolean,
    currentMatch: Boolean,
    onValueChange: (String) -> Unit,
) {
    val changed = segment.text.trim() != segment.originalText.trim()
    val borderColor = when {
        currentMatch -> MaterialTheme.colorScheme.primary
        highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (borderColor == Color.Transparent) 1.dp else 2.dp, if (borderColor == Color.Transparent) MaterialTheme.colorScheme.outlineVariant else borderColor, MaterialTheme.shapes.medium)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTimestamp(segment.startMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatTimestamp(segment.endMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (changed) {
                StatusPill("Edited", dev.sonicbrief.app.ui.components.PillTone.Warning, leadingDot = false)
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = segment.text,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 10,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ExportAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutlineChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StyledAction(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val bg = when {
        primary && enabled -> Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, dev.sonicbrief.app.ui.theme.Violet500))
        primary -> Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant))
        else -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(if (primary) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

@Composable
private fun FeedbackRow(message: String, isError: Boolean) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isError) Icons.Filled.Warning else Icons.Filled.Check,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = content)
    }
}

private fun transcriptSourceLabel(kind: TranscriptSourceKind): String = when (kind) {
    TranscriptSourceKind.AUDIO -> "Audio transcription"
    TranscriptSourceKind.MANUAL_SUBTITLE -> "Manual subtitle"
    TranscriptSourceKind.AUTOMATIC_CAPTION -> "Auto caption"
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

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "%dh %02dm".format(hours, minutes) else "%dm".format(minutes)
}

private val resultDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
