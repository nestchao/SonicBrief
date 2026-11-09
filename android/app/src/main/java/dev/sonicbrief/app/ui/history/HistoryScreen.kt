package dev.sonicbrief.app.ui.history

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.JobStatus
import dev.sonicbrief.app.ui.components.ChoicePill
import dev.sonicbrief.app.ui.components.EmptyState
import dev.sonicbrief.app.ui.components.ScreenHeader
import dev.sonicbrief.app.ui.components.SoftCard
import dev.sonicbrief.app.ui.components.SonicTextField
import dev.sonicbrief.app.ui.components.StatusPill
import dev.sonicbrief.app.ui.components.pillLabel
import dev.sonicbrief.app.ui.components.pillTone
import dev.sonicbrief.app.ui.icons.SonicIcons
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("d MMM · HH:mm").withZone(ZoneId.systemDefault())

/** Pure filter used by the screen and unit tests: matches title, creator, filename or URL. */
fun filterJobs(jobs: List<Job>, query: String): List<Job> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return jobs
    return jobs.filter { job ->
        listOf(job.title, job.creator, job.sourceFilename, job.sourceUrl)
            .any { it?.lowercase()?.contains(needle) == true }
    }
}

private enum class StatusFilter(val label: String) {
    ALL("All"),
    READY("Ready"),
    WORKING("Working"),
    FAILED("Failed"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    jobs: List<Job>,
    onOpenJob: (Job) -> Unit,
    onRename: (Job, String) -> Unit,
    onDelete: (Job) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(StatusFilter.ALL) }
    var renaming by remember { mutableStateOf<Job?>(null) }
    var deleting by remember { mutableStateOf<Job?>(null) }

    val searched = filterJobs(jobs, query)
    val visible = when (filter) {
        StatusFilter.ALL -> searched
        StatusFilter.READY -> searched.filter { it.status == JobStatus.COMPLETED }
        StatusFilter.WORKING -> searched.filter { it.status == JobStatus.ACTIVE }
        StatusFilter.FAILED -> searched.filter { it.status == JobStatus.FAILED || it.status == JobStatus.CANCELLED }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                eyebrow = "${jobs.size} item${if (jobs.size == 1) "" else "s"}",
                title = "Library",
                subtitle = "Every transcript and summary saved on this phone.",
            )
        }

        item {
            SonicTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search title, creator or link",
                leadingIcon = Icons.Filled.Search,
            )
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusFilter.entries.forEach { option ->
                    ChoicePill(
                        label = option.label,
                        selected = filter == option,
                        onClick = { filter = option },
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                EmptyState(
                    icon = if (jobs.isEmpty()) SonicIcons.FileText else Icons.Filled.Search,
                    title = if (jobs.isEmpty()) "Your library is empty" else "No matches",
                    body = if (jobs.isEmpty()) "Process a link or a file and it will appear here." else "Try a different search term or filter.",
                )
            }
        }

        items(visible, key = { it.id }) { job ->
            LibraryCard(
                job = job,
                onClick = { onOpenJob(job) },
                onRename = { renaming = job },
                onDelete = { deleting = job },
            )
        }
    }

    renaming?.let { job ->
        var text by remember(job.id) { mutableStateOf(job.displayTitle) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename item", style = MaterialTheme.typography.titleLarge) },
            text = {
                SonicTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Title",
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(job, text); renaming = null }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            },
        )
    }
    deleting?.let { job ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this item?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("The transcript and summaries on this phone will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { onDelete(job); deleting = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LibraryCard(
    job: Job,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    SoftCard(onClick = onClick, contentPadding = 0.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        SonicIcons.FileText,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(job.status.pillLabel(), job.status.pillTone())
                        Spacer(Modifier.width(8.dp))
                        Text(
                            dateFormat.format(job.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            listOfNotNull(
                job.creator?.takeIf { it.isNotBlank() }?.let { "By $it" },
                job.durationMs?.takeIf { it > 0 }?.let { formatDuration(it) },
            ).takeIf { it.isNotEmpty() }?.let { details ->
                Spacer(Modifier.height(10.dp))
                Text(
                    details.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardAction("Rename", Icons.Filled.Edit, onRename)
                CardAction("Delete", Icons.Filled.Delete, onDelete)
            }
        }
    }
}

@Composable
private fun CardAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d h %02d min".format(hours, minutes) else "%d min %02d s".format(minutes, seconds)
}
