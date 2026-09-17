package com.personal.assistant.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.assistant.AppContainer
import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.domain.TaskStateMachine
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

enum class CalendarView { DAY, WEEK, MONTH }

/** One cell of the month grid: how the day should be marked without loading its tasks. */
data class DayMarker(
    val date: LocalDate,
    val total: Int,
    val completed: Int,
    val missed: Int,
    val cancelled: Int,
) {
    val hasActivity: Boolean get() = total > 0
}

data class CalendarUiState(
    val view: CalendarView = CalendarView.MONTH,
    val anchor: LocalDate = LocalDate.now(),
    val selected: LocalDate = LocalDate.now(),
    val markers: Map<LocalDate, DayMarker> = emptyMap(),
    val selectedTasks: List<Task> = emptyList(),
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val loading: Boolean = false,
)

class CalendarViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CalendarUiState(anchor = container.now().toLocalDate()))
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        load(container.now().toLocalDate())
    }

    fun setView(view: CalendarView) {
        _state.value = _state.value.copy(view = view)
        load(_state.value.anchor)
    }

    fun select(date: LocalDate) {
        _state.value = _state.value.copy(selected = date)
        loadSelected(date)
    }

    fun next() = load(step(forward = true))

    fun previous() = load(step(forward = false))

    private fun step(forward: Boolean): LocalDate {
        val current = _state.value
        val delta = if (forward) 1L else -1L
        return when (current.view) {
            CalendarView.DAY -> current.anchor.plusDays(delta)
            CalendarView.WEEK -> current.anchor.plusWeeks(delta)
            CalendarView.MONTH -> current.anchor.plusMonths(delta)
        }
    }

    /**
     * Loads only the visible range. A calendar that read the whole table on every swipe would be the
     * app's most expensive screen for no benefit.
     */
    fun load(anchor: LocalDate) {
        _state.value = _state.value.copy(anchor = anchor, loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val preferences = container.settingsRepository.current()
            val now = container.now()
            val range = when (_state.value.view) {
                CalendarView.DAY -> DateRange.single(anchor)
                CalendarView.WEEK -> DateRange.week(anchor, preferences.firstDayOfWeek)
                // A month grid shows trailing days of the neighbouring months, so pad the query.
                CalendarView.MONTH -> DateRange(
                    DateRange.month(anchor).start.minusDays(7),
                    DateRange.month(anchor).end.plusDays(7),
                )
            }
            val tasks = container.taskRepository.range(range)
            val markers = tasks.groupBy { it.date }.mapValues { (date, forDay) ->
                var completed = 0
                var missed = 0
                var cancelled = 0
                for (task in forDay) {
                    when (TaskStateMachine.deriveForClock(task, now)) {
                        TaskStatus.COMPLETED, TaskStatus.PARTIALLY_COMPLETED -> completed++
                        TaskStatus.MISSED -> missed++
                        TaskStatus.CANCELLED, TaskStatus.SKIPPED -> cancelled++
                        else -> Unit
                    }
                }
                DayMarker(date, forDay.size, completed, missed, cancelled)
            }
            val selected = _state.value.selected
            _state.value = _state.value.copy(
                markers = markers,
                firstDayOfWeek = preferences.firstDayOfWeek,
                selectedTasks = tasks.filter { it.date == selected }
                    .sortedWith(compareBy({ it.startTime == null }, { it.startTime })),
                loading = false,
            )
        }
    }

    private fun loadSelected(date: LocalDate) {
        viewModelScope.launch(Dispatchers.IO) {
            val tasks = container.taskRepository.range(DateRange.single(date))
                .sortedWith(compareBy({ it.startTime == null }, { it.startTime }))
            _state.value = _state.value.copy(selectedTasks = tasks)
        }
    }
}
