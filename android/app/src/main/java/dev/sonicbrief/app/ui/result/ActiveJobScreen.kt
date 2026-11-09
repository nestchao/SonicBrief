package dev.sonicbrief.app.ui.result

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonicbrief.app.domain.model.Job
import dev.sonicbrief.app.ui.components.ElapsedClock
import dev.sonicbrief.app.ui.components.GradientProgress
import dev.sonicbrief.app.ui.components.IconBadge
import dev.sonicbrief.app.ui.components.SecondaryButton
import dev.sonicbrief.app.ui.components.SoftCard
import dev.sonicbrief.app.ui.components.StageTimeline
import dev.sonicbrief.app.ui.icons.SonicIcons

/**
 * Live tracking screen shown while a job is still running: the same five-step
 * timeline as the notification plus an elapsed clock and a cancel action.
 */
@Composable
fun ActiveJobScreen(
    job: Job,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val step = stepIndexFor(job.stage)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(13.dp))
                        .clickable(onClick = onBack)
                        .padding(2.dp),
                ) {
                    IconBadge(
                        icon = Icons.Filled.ArrowBack,
                        size = 40.dp,
                        tint = MaterialTheme.colorScheme.onSurface,
                        container = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Working on it", style = MaterialTheme.typography.titleMedium)
                    ElapsedClock(startedAt = job.createdAt, prefix = "Elapsed ")
                }
            }
        }

        item {
            SoftCard {
                Text(
                    job.displayTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (step in 0..4) "Step ${step + 1} of 5" else "Queued",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${job.progress}%",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                GradientProgress(progress = job.progress / 100f, height = 10.dp)
            }
        }

        item {
            SoftCard {
                StageTimeline(
                    currentStep = step,
                    activeDetail = job.stageDetail,
                )
            }
        }

        item {
            Text(
                "You can leave — progress continues in the background and the notification stays up to date.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "Back",
                    onClick = onBack,
                    icon = Icons.Filled.ArrowBack,
                    fill = false,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Cancel job",
                    onClick = onCancel,
                    icon = SonicIcons.Timer,
                    fill = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
