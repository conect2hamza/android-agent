package com.personal.assistant.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.assistant.AppContainer
import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.domain.ReportGenerator
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.report.TaskTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DashboardUiState(
    val totals: TaskTotals = TaskTotals(),
    val trackedMinutes: Int = 0,
    val plannedMinutes: Int = 0,
    val notificationsBlocked: Boolean = false,
    val exactAlarmsBlocked: Boolean = false,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val today: LocalDate get() = container.now().toLocalDate()

    val tasks: StateFlow<List<Task>> = container.taskRepository
        .observeDay(today)
        .map { list -> list.sortedWith(compareBy({ it.startTime == null }, { it.startTime })) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = container.now()
            val range = DateRange.single(now.toLocalDate())
            val report = ReportGenerator(container.taskRepository.categories()).daily(
                date = now.toLocalDate(),
                tasks = container.taskRepository.range(range),
                logs = container.taskRepository.logsInRange(range),
                now = now,
            )
            _state.value = DashboardUiState(
                totals = report.totals,
                trackedMinutes = report.trackedMinutes,
                plannedMinutes = report.plannedMinutes,
                // Surfaced as a banner: the specification requires telling the user when Android
                // settings will stop reminders arriving, rather than failing silently.
                notificationsBlocked = !container.notifier.areNotificationsEnabled(),
                exactAlarmsBlocked = !container.alarmScheduler.canScheduleExact(),
            )
        }
    }

    fun complete(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        container.taskRepository.setProgress(taskId, 100, container.now())
        refresh()
    }

    fun start(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        container.taskRepository.startTracking(taskId, container.now())
        refresh()
    }
}
