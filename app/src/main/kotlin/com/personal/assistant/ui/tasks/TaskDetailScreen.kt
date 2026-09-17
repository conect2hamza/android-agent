package com.personal.assistant.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.assistant.AppContainer
import com.personal.assistant.core.domain.TimeTracker
import com.personal.assistant.core.model.ActivityLog
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.ui.components.CompletionMeter
import com.personal.assistant.ui.components.StatusChip
import com.personal.assistant.ui.containerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One task in full: its schedule, its real tracked time, and the log of what actually happened to it.
 *
 * The timeline matters more than it looks. It is the only place the user can see the difference
 * between what they planned and what they did, which is the whole point of tracking.
 */
@Composable
fun TaskDetailScreen(container: AppContainer, taskId: Long, onBack: () -> Unit) {
    val viewModel = containerViewModel(container, key = "tasks") { TasksViewModel(it) }
    var refreshKey by remember { mutableStateOf(0) }

    val data by produceState<Pair<Task?, List<ActivityLog>>>(
        initialValue = null to emptyList(),
        taskId,
        refreshKey,
    ) {
        value = withContext(Dispatchers.IO) {
            container.taskRepository.task(taskId) to container.taskRepository.logsFor(taskId)
        }
    }

    val (task, logs) = data
    if (task == null) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("This task no longer exists.", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }

    val trackedMinutes = remember(logs) { TimeTracker.tracked(logs, container.now()).activeMinutes }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text(task.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        StatusChip(task.status)
        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Scheduled ${task.date}", style = MaterialTheme.typography.bodyLarge)
                task.startTime?.let { start ->
                    Text(
                        "From $start" + (task.endTime?.let { " to $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                task.reminderMinutes?.let {
                    Text("Reminder ${TimeTracker.format(it)} before", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Planned ${TimeTracker.format(task.plannedMinutes)} - " +
                        "tracked ${TimeTracker.format(trackedMinutes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                task.notes?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Progress: ${task.progress}%", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        CompletionMeter(task.progress.toDouble())
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(0, 25, 50, 75, 100)) { percent ->
                FilterChip(
                    selected = task.progress == percent,
                    onClick = {
                        viewModel.setProgress(task.id, percent)
                        refreshKey++
                    },
                    label = { Text("$percent%") },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!task.status.isActive && !task.status.isTerminal) {
                Button(onClick = {
                    viewModel.changeStatus(task.id, TaskStatus.STARTED)
                    refreshKey++
                }) { Text("Start") }
            }
            if (task.status != TaskStatus.COMPLETED) {
                OutlinedButton(onClick = {
                    viewModel.setProgress(task.id, 100)
                    refreshKey++
                }) { Text("Complete") }
            }
            TextButton(onClick = {
                viewModel.changeStatus(task.id, TaskStatus.CANCELLED)
                refreshKey++
            }) { Text("Cancel task") }
        }

        Spacer(Modifier.height(20.dp))
        Text("Activity", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (logs.isEmpty()) {
            Text(
                "Nothing recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        logs.forEach { log ->
            Text(
                "${log.timestamp.toLocalDate()} ${log.timestamp.toLocalTime().withSecond(0).withNano(0)}  " +
                    log.event.name.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    LaunchedEffect(task.id) { viewModel.refresh() }
}
