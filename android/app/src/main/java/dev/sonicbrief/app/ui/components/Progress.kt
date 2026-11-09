package dev.sonicbrief.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonicbrief.app.ui.result.ProgressStep
import dev.sonicbrief.app.ui.result.formatElapsed
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Ticking clock bound to a job's start time ("Elapsed 3:42"). */
@Composable
fun ElapsedClock(
    startedAt: Instant,
    modifier: Modifier = Modifier,
    prefix: String = "",
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }
    Text(
        "$prefix${formatElapsed(nowMs - startedAt.toEpochMilli())}",
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Vertical five-step tracker. [currentStep] is 0-based; -1 means still queued, in which case the
 * first step shows as the pending head of the queue.
 */
@Composable
fun StageTimeline(
    currentStep: Int,
    activeDetail: String?,
    modifier: Modifier = Modifier,
) {
    val steps = remember { ProgressStep.entries.toList() }
    Column(modifier = modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            val state = when {
                currentStep < 0 -> if (index == 0) StepState.ACTIVE else StepState.TODO
                index < currentStep -> StepState.DONE
                index == currentStep -> StepState.ACTIVE
                else -> StepState.TODO
            }
            StageRow(
                step = step,
                state = state,
                detail = if (state == StepState.ACTIVE) activeDetail else null,
                isLast = index == steps.lastIndex,
            )
        }
    }
}

private enum class StepState { DONE, ACTIVE, TODO }

@Composable
private fun StageRow(
    step: ProgressStep,
    state: StepState,
    detail: String?,
    isLast: Boolean,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            StepState.DONE -> MaterialTheme.colorScheme.primaryContainer
                            StepState.ACTIVE -> MaterialTheme.colorScheme.primary
                            StepState.TODO -> Color.Transparent
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    StepState.DONE -> Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    StepState.ACTIVE -> Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimary),
                    )
                    StepState.TODO -> Text(
                        "${ProgressStep.entries.indexOf(step) + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            if (!isLast) {
                Box(
                    Modifier
                        .width(2.dp)
                        .height(14.dp)
                        .background(
                            if (state == StepState.DONE) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.padding(top = 3.dp, bottom = if (isLast) 0.dp else 14.dp)) {
            Text(
                step.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (state == StepState.TODO) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state == StepState.ACTIVE && !detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Single-line "Step X of 5 · label" summary used above progress bars. */
@Composable
fun StepSummary(
    currentStep: Int,
    modifier: Modifier = Modifier,
) {
    val text = if (currentStep in 0..4) {
        "Step ${currentStep + 1} of 5 · ${ProgressStep.entries[currentStep].label}"
    } else {
        "Queued — starting soon"
    }
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}
