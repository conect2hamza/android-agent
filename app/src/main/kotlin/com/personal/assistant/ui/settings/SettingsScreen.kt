package com.personal.assistant.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.personal.assistant.AppContainer
import com.personal.assistant.data.repository.ExportPayload
import com.personal.assistant.data.repository.ImportMode
import com.personal.assistant.data.repository.ThemeChoice
import com.personal.assistant.ui.containerViewModel
import java.time.DayOfWeek

@Composable
fun SettingsScreen(container: AppContainer) {
    val viewModel = containerViewModel(container, key = "settings") { SettingsViewModel(it) }
    val settings by viewModel.settings.collectAsState()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var passphrase by remember { mutableStateOf("") }
    var includeChat by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var importPassphrase by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // The system file picker is the only way data leaves the app: the user chooses the destination and
    // nothing is written anywhere else.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val payload = state.pendingExport
        if (uri != null && payload != null) {
            writeText(context, uri, payload.text())
        }
        viewModel.consumeExport()
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readText(context, uri)
        if (text == null) return@rememberLauncherForActivityResult
        if (container.crypto.isEncryptedBackup(text)) {
            pendingImportUri = uri
            importPassphrase = ""
        } else {
            viewModel.import(text, null, ImportMode.MERGE)
        }
    }

    LaunchedEffect(state.pendingExport) {
        val payload = state.pendingExport ?: return@LaunchedEffect
        saveLauncher.launch(payload.suggestedFileName())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        state.message?.let { message ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    }
                }
            }
        }

        item {
            Section("General") {
                Text("Theme", style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ThemeChoice.entries.toList()) { choice ->
                        FilterChip(
                            selected = settings.theme == choice,
                            onClick = { viewModel.setTheme(choice) },
                            label = { Text(choice.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("24-hour clock", settings.use24HourClock, viewModel::set24Hour)
                Spacer(Modifier.height(8.dp))
                Text("First day of week", style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) { day ->
                        FilterChip(
                            selected = settings.firstDayOfWeek == day,
                            onClick = { viewModel.setFirstDayOfWeek(day) },
                            label = { Text(day.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
        }

        item {
            Section("Notifications") {
                if (!state.notificationsEnabled) {
                    Text(
                        "Notifications are blocked in Android settings. Reminders will not arrive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (!state.exactAlarmsAllowed) {
                    Text(
                        "Exact alarms are not permitted, so reminders may be delayed by a few minutes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("Default reminder", style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(com.personal.assistant.core.model.Task.REMINDER_CHOICES) { minutes ->
                        FilterChip(
                            selected = settings.defaultReminderMinutes == minutes,
                            onClick = { viewModel.setDefaultReminder(minutes) },
                            label = { Text(if (minutes >= 60) "${minutes / 60} h" else "$minutes min") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("Daily summary", settings.dailySummaryEnabled) { viewModel.setDailySummary(it) }
                ToggleRow("Weekly report", settings.weeklyReportEnabled) { viewModel.setWeeklyReport(it) }
                ToggleRow(
                    "Progress check-ins during long tasks",
                    settings.progressRemindersEnabled,
                    viewModel::setProgressReminders,
                )
                ToggleRow(
                    "Warn about overlapping tasks",
                    settings.conflictWarningsEnabled,
                    viewModel::setConflictWarnings,
                )
            }
        }

        item {
            Section("Language model") {
                Text(
                    "The assistant understands English, Urdu and Roman Urdu without a model. Installing one " +
                        "only helps with unusual phrasing, and it is loaded on demand and released when idle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow("Use an installed model", settings.aiEnabled, viewModel::setAiEnabled)
                Spacer(Modifier.height(8.dp))
                if (state.installedModels.isEmpty()) {
                    Text(
                        "No model installed. Place a model file in the app's models folder to use one.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    state.installedModels.forEach { model ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text("${model.sizeMb} MB", style = MaterialTheme.typography.labelSmall)
                            }
                            FilterChip(
                                selected = settings.aiModelId == model.id,
                                onClick = { viewModel.setModel(model.id) },
                                label = { Text("Use") },
                            )
                            TextButton(onClick = { viewModel.deleteModel(model.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        item {
            Section("Privacy and security") {
                ToggleRow("Remember things I tell you", settings.memoryEnabled, viewModel::setMemoryEnabled)
                ToggleRow(
                    "Encrypt notes, memories and chat",
                    settings.encryptionEnabled && state.encryptionAvailable,
                    viewModel::setEncryptionEnabled,
                )
                if (!state.encryptionAvailable) {
                    Text(
                        "This device's secure key store is unavailable, so encryption is off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ToggleRow("Require unlock to open the app", settings.appLockEnabled, viewModel::setAppLockEnabled)
                Spacer(Modifier.height(8.dp))
                Text(
                    "This app has no internet permission. Nothing you write can leave the device except " +
                        "through an export you save yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Section("Your data") {
                state.storage.forEach { (label, count) ->
                    Text("$label: $count", style = MaterialTheme.typography.bodyMedium)
                }
                if (state.modelBytes > 0) {
                    Text(
                        "Model files: ${state.modelBytes / (1024 * 1024)} MB",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(10.dp))
                ToggleRow("Include chat history in exports", includeChat) { includeChat = it }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.exportJson(includeChat) }, enabled = !state.busy) {
                        Text("Export JSON")
                    }
                    Button(onClick = { viewModel.exportCsv() }, enabled = !state.busy) { Text("Export CSV") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Backup passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.exportEncryptedBackup(passphrase, includeChat) },
                        enabled = !state.busy,
                    ) { Text("Encrypted backup") }
                    Button(
                        onClick = { openLauncher.launch(arrayOf("*/*")) },
                        enabled = !state.busy,
                    ) { Text("Restore") }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { confirmDelete = true }) { Text("Delete all data") }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete everything?") },
            text = {
                Text(
                    "Every task, activity record, memory and message is removed from this device. " +
                        "This cannot be undone, and an export made beforehand is the only way back.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteAllData()
                    confirmDelete = false
                }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    importPassphrase?.let { current ->
        AlertDialog(
            onDismissRequest = {
                importPassphrase = null
                pendingImportUri = null
            },
            title = { Text("Encrypted backup") },
            text = {
                OutlinedTextField(
                    value = current,
                    onValueChange = { importPassphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    val uri = pendingImportUri
                    val text = uri?.let { readText(context, it) }
                    if (text != null) viewModel.import(text, current, ImportMode.REPLACE)
                    importPassphrase = null
                    pendingImportUri = null
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    importPassphrase = null
                    pendingImportUri = null
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun ExportPayload.text(): String = when (this) {
    is ExportPayload.Json -> text
    is ExportPayload.Csv -> text
    is ExportPayload.EncryptedBackup -> text
}

private fun ExportPayload.suggestedFileName(): String = when (this) {
    is ExportPayload.Json -> "assistant-export.json"
    is ExportPayload.Csv -> "assistant-tasks.csv"
    is ExportPayload.EncryptedBackup -> "assistant-backup.enc"
}

private fun writeText(context: Context, uri: Uri, text: String) {
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
        }
    }
}

/** Import files are user-chosen, so the size cap is a guard against a mistaken pick, not an attack. */
private fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val bytes = stream.readBytes()
        if (bytes.size > MAX_IMPORT_BYTES) null else String(bytes, Charsets.UTF_8)
    }
}.getOrNull()

private const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
