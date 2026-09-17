package com.personal.assistant.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.assistant.AppContainer
import com.personal.assistant.core.model.Priority
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.ui.components.TaskCard
import com.personal.assistant.ui.containerViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Manual task management, and the app's answer to "what if the language layer fails".
 *
 * Nothing on this screen goes anywhere near the parser or a model: it reads and writes the repository
 * directly, so creating, editing, completing and deleting tasks keep working under any AI failure.
 */
@Composable
fun TasksScreen(container: AppContainer, use24HourClock: Boolean, onOpenTask: (Long) -> Unit) {
    val viewModel = containerViewModel(container) { TasksViewModel(it) }
    val state by viewModel.state.collectAsState()
    var editing by remember { mutableStateOf<Task?>(null) }
    var creating by remember { mutableStateOf(false) }
    val today = container.now().toLocalDate()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Text("+") }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search tasks and notes") },
                singleLine = true,
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TaskFilter.entries.toList()) { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = {
                            Text(filter.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                        },
                    )
                }
            }

            state.message?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.tasks.isEmpty() && !state.loading) {
                    item {
                        Text(
                            "No tasks here yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        today = today,
                        use24HourClock = use24HourClock,
                        onClick = { onOpenTask(task.id) },
                        onComplete = { viewModel.setProgress(task.id, 100) },
                        onStart = { viewModel.changeStatus(task.id, TaskStatus.STARTED) },
                        onEdit = { editing = task },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (creating) {
        TaskEditorDialog(
            initial = null,
            categories = state.categories.map { it.name },
            today = today,
            onDismiss = { creating = false },
            onSave = { title, date, start, end, category, priority, reminder, notes ->
                viewModel.create(title, date, start, end, category, priority, reminder, notes)
                creating = false
            },
        )
    }

    editing?.let { task ->
        TaskEditorDialog(
            initial = task,
            categories = state.categories.map { it.name },
            today = today,
            onDismiss = { editing = null },
            onDelete = {
                viewModel.delete(task.id)
                editing = null
            },
            onSave = { title, date, start, end, _, priority, reminder, notes ->
                viewModel.update(
                    task.copy(
                        title = title,
                        date = date,
                        startTime = start,
                        endTime = end,
                        priority = priority,
                        reminderMinutes = reminder,
                        notes = notes,
                    ),
                )
                editing = null
            },
        )
    }
}

/**
 * Dates and times are typed, with chips for the common answers.
 *
 * The platform pickers in Material 3 are still marked experimental, and the chat screen is where most
 * tasks are created anyway. A plain field that accepts `2026-09-18` and `16:00`, backed by one-tap
 * chips for today, tomorrow and the usual hours, is predictable and costs nothing in API risk.
 */
@Composable
private fun TaskEditorDialog(
    initial: Task?,
    categories: List<String>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (String, LocalDate, LocalTime?, LocalTime?, String?, Priority, Int?, String?) -> Unit,
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var dateText by remember { mutableStateOf((initial?.date ?: today).toString()) }
    var startText by remember { mutableStateOf(initial?.startTime?.toString() ?: "") }
    var endText by remember { mutableStateOf(initial?.endTime?.toString() ?: "") }
    var category by remember { mutableStateOf<String?>(null) }
    var priority by remember { mutableStateOf(initial?.priority ?: Priority.NORMAL) }
    var reminderText by remember { mutableStateOf(initial?.reminderMinutes?.toString() ?: "30") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }

    fun parseDate(): LocalDate? = try {
        LocalDate.parse(dateText.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    fun parseTime(text: String): LocalTime? = text.trim().takeIf { it.isNotEmpty() }?.let {
        try {
            LocalTime.parse(if (it.length == 5) it else "0$it")
        } catch (_: DateTimeParseException) {
            null
        }
    }

    val dateValid = parseDate() != null
    val timeValid = startText.isBlank() || parseTime(startText) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New task" else "Edit task") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    isError = !dateValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { dateText = today.toString() }, label = { Text("Today") })
                    AssistChip(
                        onClick = { dateText = today.plusDays(1).toString() },
                        label = { Text("Tomorrow") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("Start (HH:MM, optional)") },
                    isError = !timeValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("09:00", "14:00", "16:00", "20:00").forEach { option ->
                        AssistChip(onClick = { startText = option }, label = { Text(option) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("End (HH:MM, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Priority", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { option ->
                        FilterChip(
                            selected = priority == option,
                            onClick = { priority = option },
                            label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                if (initial == null && categories.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Category", style = MaterialTheme.typography.labelSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories) { name ->
                            FilterChip(
                                selected = category == name,
                                onClick = { category = if (category == name) null else name },
                                label = { Text(name) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reminderText,
                    onValueChange = { reminderText = it.filter(Char::isDigit) },
                    label = { Text("Remind me this many minutes before") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && dateValid && timeValid,
                onClick = {
                    val date = parseDate() ?: return@Button
                    onSave(
                        title.trim(),
                        date,
                        parseTime(startText),
                        parseTime(endText),
                        category,
                        priority,
                        reminderText.toIntOrNull(),
                        notes.takeIf { it.isNotBlank() },
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
