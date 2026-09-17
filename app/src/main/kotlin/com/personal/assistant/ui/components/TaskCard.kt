package com.personal.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personal.assistant.core.domain.TimeTracker
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.ui.theme.StatusColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val TIME_12 = DateTimeFormatter.ofPattern("h:mm a")
private val TIME_24 = DateTimeFormatter.ofPattern("HH:mm")

fun statusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.COMPLETED -> StatusColors.completed
    TaskStatus.PARTIALLY_COMPLETED -> StatusColors.completed
    TaskStatus.STARTED, TaskStatus.IN_PROGRESS -> StatusColors.inProgress
    TaskStatus.MISSED -> StatusColors.missed
    TaskStatus.CANCELLED, TaskStatus.SKIPPED -> StatusColors.cancelled
    else -> StatusColors.pending
}

fun statusLabel(status: TaskStatus): String =
    status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
fun StatusChip(status: TaskStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(statusColor(status).copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = statusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(status),
        )
    }
}

/**
 * The task card from the specification: title, when, reminder, status, progress, and the two actions
 * worth having within reach. Everything else lives behind the card's tap target rather than crowding a
 * list the user scrolls through daily.
 */
@Composable
fun TaskCard(
    task: Task,
    today: LocalDate,
    use24HourClock: Boolean,
    trackedMinutes: Int? = null,
    onClick: () -> Unit = {},
    onComplete: (() -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val formatter = if (use24HourClock) TIME_24 else TIME_12

    // A plain Card with a clickable modifier rather than the clickable Card overload, which is still
    // experimental in some Material 3 releases.
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // A colour bar rather than a coloured card: status stays legible at a glance without
            // making a screen of cards look like a paint chart.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(if (task.progress > 0) 108.dp else 96.dp)
                    .background(statusColor(task.status)),
            )
            Column(modifier = Modifier.padding(14.dp).weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(
                            when (task.date) {
                                today -> "Today"
                                today.plusDays(1) -> "Tomorrow"
                                today.minusDays(1) -> "Yesterday"
                                else -> task.date.toString()
                            },
                        )
                        task.startTime?.let {
                            append(" - ")
                            append(it.format(formatter))
                            task.endTime?.let { end -> append(" to ${end.format(formatter)}") }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                task.reminderMinutes?.takeIf { task.startTime != null }?.let { lead ->
                    Text(
                        text = "Reminder ${TimeTracker.format(lead)} before",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                trackedMinutes?.takeIf { it > 0 }?.let { minutes ->
                    Text(
                        text = "Tracked ${TimeTracker.format(minutes)}" +
                            (task.plannedMinutes.takeIf { it > 0 }?.let { " of ${TimeTracker.format(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(task.status)
                    if (task.progress in 1..99) {
                        Spacer(Modifier.width(8.dp))
                        Text("${task.progress}%", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            CompletionMeter(rate = task.progress.toDouble())
                        }
                    }
                }

                if (onComplete != null || onStart != null || onEdit != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onStart != null && !task.status.isActive && !task.status.isTerminal) {
                            TextButton(onClick = onStart) { Text("Start") }
                        }
                        if (onComplete != null && task.status != TaskStatus.COMPLETED) {
                            OutlinedButton(onClick = onComplete) { Text("Complete") }
                        }
                        if (onEdit != null) {
                            TextButton(onClick = onEdit) { Text("Edit") }
                        }
                    }
                }
            }
            Box(modifier = Modifier.size(1.dp))
        }
    }
}
