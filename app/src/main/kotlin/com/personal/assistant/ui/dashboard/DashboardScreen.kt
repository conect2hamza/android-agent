package com.personal.assistant.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.assistant.AppContainer
import com.personal.assistant.core.domain.TimeTracker
import com.personal.assistant.ui.components.CompletionMeter
import com.personal.assistant.ui.components.StatusBarChart
import com.personal.assistant.ui.components.TaskCard
import com.personal.assistant.ui.containerViewModel
import com.personal.assistant.ui.theme.StatusColors

@Composable
fun DashboardScreen(
    container: AppContainer,
    use24HourClock: Boolean,
    onOpenTask: (Long) -> Unit,
    onOpenMemory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val viewModel = containerViewModel(container) { DashboardViewModel(it) }
    val tasks by viewModel.tasks.collectAsState()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(tasks.size) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenMemory) { Text("Memory") }
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }

        // Reliability warnings come first: a planner whose reminders cannot fire is worth saying so
        // loudly, not burying in settings.
        if (state.notificationsBlocked) {
            item {
                WarningCard(
                    "Notifications are turned off",
                    "Reminders and follow-ups won't reach you until you allow notifications for this app " +
                        "in Android settings.",
                )
            }
        }
        if (state.exactAlarmsBlocked) {
            item {
                WarningCard(
                    "Exact alarms are not allowed",
                    "Reminders will still arrive, but Android may delay them by several minutes. " +
                        "Allow alarms and reminders in Android settings for on-time notifications.",
                )
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Today", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${state.totals.total} tasks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("Completed", state.totals.completed, StatusColors.completed)
                        Stat("In progress", state.totals.inProgress, StatusColors.inProgress)
                        Stat("Pending", state.totals.pending, StatusColors.pending)
                        Stat("Missed", state.totals.missed, StatusColors.missed)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Completion ${state.totals.completionRate.toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    CompletionMeter(state.totals.completionRate)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Planned ${TimeTracker.format(state.plannedMinutes)} - " +
                            "tracked ${TimeTracker.format(state.trackedMinutes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.totals.total > 0) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Breakdown", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        StatusBarChart(
                            completed = state.totals.completed,
                            pending = state.totals.pending + state.totals.inProgress,
                            missed = state.totals.missed,
                        )
                    }
                }
            }
        }

        item { Text("Schedule", style = MaterialTheme.typography.titleMedium) }

        if (tasks.isEmpty()) {
            item {
                Text(
                    "Nothing scheduled today. Tell the assistant what you're planning.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                today = container.now().toLocalDate(),
                use24HourClock = use24HourClock,
                onClick = { onOpenTask(task.id) },
                onComplete = { viewModel.complete(task.id) },
                onStart = { viewModel.start(task.id) },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = color)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WarningCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
