package com.personal.assistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.assistant.AppContainer
import com.personal.assistant.ai.InstalledModel
import com.personal.assistant.data.repository.AppSettings
import com.personal.assistant.data.repository.ExportPayload
import com.personal.assistant.data.repository.ImportMode
import com.personal.assistant.data.repository.ResponseLength
import com.personal.assistant.data.repository.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

data class SettingsUiState(
    val installedModels: List<InstalledModel> = emptyList(),
    val storage: Map<String, Int> = emptyMap(),
    val modelBytes: Long = 0,
    val notificationsEnabled: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    val encryptionAvailable: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    /** Text to hand to the system's file picker; consumed by the screen once written. */
    val pendingExport: ExportPayload? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        _state.value = _state.value.copy(
            installedModels = container.modelStore.installed(),
            modelBytes = container.modelStore.totalBytes(),
            storage = container.backupRepository.storageEstimate(),
            notificationsEnabled = container.notifier.areNotificationsEnabled(),
            exactAlarmsAllowed = container.alarmScheduler.canScheduleExact(),
            encryptionAvailable = container.crypto.isAvailable,
        )
    }

    // -------------------------------------------------------------- preferences

    fun setTheme(value: ThemeChoice) = edit { it.setTheme(value) }
    fun set24Hour(value: Boolean) = edit { it.set24HourClock(value) }
    fun setFirstDayOfWeek(value: DayOfWeek) = edit { it.setFirstDayOfWeek(value) }
    fun setDefaultReminder(value: Int) = edit { it.setDefaultReminderMinutes(value) }
    fun setResponseLength(value: ResponseLength) = edit { it.setResponseLength(value) }
    fun setMemoryEnabled(value: Boolean) = edit { it.setMemoryEnabled(value) }
    fun setEncryptionEnabled(value: Boolean) = edit { it.setEncryptionEnabled(value) }
    fun setAppLockEnabled(value: Boolean) = edit { it.setAppLockEnabled(value) }
    fun setConflictWarnings(value: Boolean) = edit { it.setConflictWarningsEnabled(value) }
    fun setProgressReminders(value: Boolean) = edit { it.setProgressRemindersEnabled(value) }
    fun setAiEnabled(value: Boolean) = edit { it.setAiEnabled(value) }
    fun setModel(id: String) = edit { it.setAiModelId(id) }

    /** Changing when summaries arrive has to re-arm the jobs, not just store the value. */
    fun setDailySummary(enabled: Boolean, time: LocalTime? = null) = viewModelScope.launch(Dispatchers.IO) {
        container.settingsRepository.setDailySummaryEnabled(enabled)
        time?.let { container.settingsRepository.setDailySummaryTime(it) }
        container.alarmScheduler.rescheduleSummaries()
    }

    fun setWeeklyReport(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        container.settingsRepository.setWeeklyReportEnabled(enabled)
        container.alarmScheduler.rescheduleSummaries()
    }

    private fun edit(block: suspend (com.personal.assistant.data.repository.SettingsRepository) -> Unit) =
        viewModelScope.launch(Dispatchers.IO) { block(container.settingsRepository) }

    // ------------------------------------------------------------ data transfer

    fun exportJson(includeChat: Boolean) = busy {
        _state.value = _state.value.copy(
            pendingExport = container.backupRepository.exportJson(includeChat, container.now()),
            message = "Export ready. Choose where to save it.",
        )
    }

    fun exportCsv() = busy {
        _state.value = _state.value.copy(
            pendingExport = container.backupRepository.exportTasksCsv(),
            message = "Export ready. Choose where to save it.",
        )
    }

    fun exportEncryptedBackup(passphrase: String, includeChat: Boolean) = busy {
        if (passphrase.length < MIN_PASSPHRASE) {
            _state.value = _state.value.copy(
                message = "Use a passphrase of at least $MIN_PASSPHRASE characters.",
            )
            return@busy
        }
        _state.value = _state.value.copy(
            pendingExport = container.backupRepository.exportEncryptedBackup(
                passphrase.toCharArray(),
                includeChat,
                container.now(),
            ),
            message = "Encrypted backup ready. It cannot be read without this passphrase.",
        )
    }

    fun consumeExport() {
        _state.value = _state.value.copy(pendingExport = null)
    }

    fun import(fileText: String, passphrase: String?, mode: ImportMode) = busy {
        val inspected = container.backupRepository.inspect(fileText, passphrase?.toCharArray())
        if (!inspected.succeeded || inspected.bundle == null) {
            _state.value = _state.value.copy(message = inspected.summary())
            return@busy
        }
        val restored = container.backupRepository.restore(inspected.bundle!!, mode, container.now())
        _state.value = _state.value.copy(message = restored.summary())
        refresh()
    }

    fun deleteAllData() = busy {
        container.backupRepository.deleteEverything()
        _state.value = _state.value.copy(message = "All local data deleted.")
        refresh()
    }

    fun deleteModel(id: String) = busy {
        container.modelStore.delete(id)
        if (settings.value.aiModelId == id) container.settingsRepository.setAiModelId("none")
        refresh()
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun busy(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        _state.value = _state.value.copy(busy = true)
        runCatching { block() }.onFailure { error ->
            _state.value = _state.value.copy(message = error.message ?: "That didn't work.")
        }
        _state.value = _state.value.copy(busy = false)
    }

    companion object {
        const val MIN_PASSPHRASE = 8
    }
}
