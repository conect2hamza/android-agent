package com.personal.assistant.ui.memory

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.assistant.AppContainer
import com.personal.assistant.core.model.Memory
import com.personal.assistant.core.model.MemoryCategory
import com.personal.assistant.ui.containerViewModel
import com.personal.assistant.ui.settings.SettingsViewModel

/**
 * Everything the assistant remembers, in one list the user can edit item by item.
 *
 * This screen is the reason a persistent memory is defensible: nothing is hidden, nothing is
 * inferred behind the user's back, every row can be corrected or deleted, and the whole feature has an
 * off switch that takes effect on the next message.
 */
@Composable
fun MemoryScreen(container: AppContainer) {
    val viewModel = containerViewModel(container) { MemoryViewModel(it) }
    val settingsViewModel = containerViewModel(container, key = "settings") { SettingsViewModel(it) }
    val memories by viewModel.memories.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()

    var editing by remember { mutableStateOf<Memory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Remember things I tell you", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Stored on this device only, and encrypted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.memoryEnabled,
                    onCheckedChange = settingsViewModel::setMemoryEnabled,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }) { Text("Add") }
            if (memories.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Delete all") }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            if (memories.isEmpty()) {
                item {
                    Text(
                        "Nothing remembered yet. Say something like \"I usually work on WordPress at night\" " +
                            "and it will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(memories, key = { it.id }) { memory ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(memory.content, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${memory.category.name.lowercase().replaceFirstChar { it.uppercase() }} - " +
                                "importance ${memory.importance}/5",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = memory }) { Text("Edit") }
                            TextButton(onClick = { viewModel.delete(memory.id) }) { Text("Delete") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (adding) {
        MemoryEditorDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { content, category, importance ->
                viewModel.add(content, category, importance)
                adding = false
            },
        )
    }

    editing?.let { memory ->
        MemoryEditorDialog(
            initial = memory,
            onDismiss = { editing = null },
            onSave = { content, category, importance ->
                viewModel.update(memory.copy(content = content, category = category, importance = importance))
                editing = null
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete every memory?") },
            text = { Text("This cannot be undone. Your tasks and reports are not affected.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteAll()
                    confirmClear = false
                }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemoryEditorDialog(
    initial: Memory?,
    onDismiss: () -> Unit,
    onSave: (String, MemoryCategory, Int) -> Unit,
) {
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: MemoryCategory.NOTE) }
    var importance by remember { mutableStateOf(initial?.importance ?: 3) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add memory" else "Edit memory") },
        text = {
            Column {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("What should I remember?") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("Category", style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MemoryCategory.entries.toList()) { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = { category = option },
                            label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Importance", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { level ->
                        FilterChip(
                            selected = importance == level,
                            onClick = { importance = level },
                            label = { Text(level.toString()) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = content.isNotBlank(),
                onClick = { onSave(content.trim(), category, importance) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
