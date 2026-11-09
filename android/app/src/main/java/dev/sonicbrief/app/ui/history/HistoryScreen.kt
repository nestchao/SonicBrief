package dev.sonicbrief.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.domain.model.JobStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Pure filter used by the screen and unit tests: matches title, creator, filename or URL. */
fun filterJobs(jobs: List<Job>, query: String): List<Job> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return jobs
    return jobs.filter { job ->
        listOf(job.title, job.creator, job.sourceFilename, job.sourceUrl)
            .any { it?.lowercase()?.contains(needle) == true }
    }
}

@Composable
fun HistoryScreen(
    jobs: List<Job>,
    onOpenJob: (Job) -> Unit,
    onRename: (Job, String) -> Unit,
    onDelete: (Job) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Job?>(null) }
    var deleting by remember { mutableStateOf<Job?>(null) }
    val visible = filterJobs(jobs, query)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("History", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search title or creator") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (visible.isEmpty()) {
            Text(if (jobs.isEmpty()) "Nothing here yet — process a link or file first." else "No matches.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visible, key = { it.id }) { job ->
                Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenJob(job) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(job.displayTitle, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(job.creator, job.status.name.lowercase(), dateFormat.format(job.createdAt)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (job.status == JobStatus.FAILED) {
                            job.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        Row {
                            TextButton(onClick = { renaming = job }) { Text("Rename") }
                            TextButton(onClick = { deleting = job }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { job ->
        var text by remember(job.id) { mutableStateOf(job.displayTitle) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(job, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { job ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this item?") },
            text = { Text("The transcript and summaries on this phone will be removed. This can't be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(job); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
