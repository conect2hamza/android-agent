package com.personal.assistant.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.assistant.AppContainer
import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.ai.TaskDraft
import com.personal.assistant.core.domain.Conflict
import com.personal.assistant.core.domain.TaskStateMachine
import com.personal.assistant.core.model.Category
import com.personal.assistant.core.model.Priority
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

enum class TaskFilter { ALL, TODAY, UPCOMING, IN_PROGRESS, COMPLETED, MISSED }

data class TasksUiState(
    val filter: TaskFilter = TaskFilter.TODAY,
    val query: String = "",
    val tasks: List<Task> = emptyList(),
    val categories: List<Category> = emptyList(),
    val conflicts: List<Conflict> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

/**
 * The manual task list.
 *
 * This screen exists so the app is fully usable with no language understanding at all -- the
 * specification requires that creating, editing and completing tasks keeps working when the model is
 * missing or broken, and every action here goes straight to the repository without touching the parser.
 */
class TasksViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(TasksUiState())
    val state: StateFlow<TasksUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun setFilter(filter: TaskFilter) {
        _state.value = _state.value.copy(filter = filter)
        refresh()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val now = container.now()
            val today = now.toLocalDate()
            val current = _state.value

            val pool = when {
                current.query.isNotBlank() -> container.taskRepository.search(current.query)
                current.filter == TaskFilter.TODAY -> container.taskRepository.range(DateRange.single(today))
                current.filter == TaskFilter.UPCOMING ->
                    container.taskRepository.range(DateRange(today, today.plusDays(30)))
                else -> container.taskRepository.range(DateRange(today.minusDays(60), today.plusDays(60)))
            }

            val filtered = pool.filter { task ->
                when (current.filter) {
                    TaskFilter.ALL -> true
                    TaskFilter.TODAY -> task.date == today
                    TaskFilter.UPCOMING -> !task.date.isBefore(today) && !task.status.isTerminal
                    TaskFilter.IN_PROGRESS -> task.status.isActive
                    TaskFilter.COMPLETED ->
                        TaskStateMachine.deriveForClock(task, now) == TaskStatus.COMPLETED
                    TaskFilter.MISSED ->
                        TaskStateMachine.deriveForClock(task, now) == TaskStatus.MISSED
                }
            }.sortedWith(compareBy({ it.date }, { it.startTime == null }, { it.startTime }))

            _state.value = _state.value.copy(
                tasks = filtered,
                categories = container.taskRepository.categories(),
                loading = false,
            )
        }
    }

    fun create(
        title: String,
        date: LocalDate,
        start: LocalTime?,
        end: LocalTime?,
        categoryName: String?,
        priority: Priority,
        reminderMinutes: Int?,
        notes: String?,
    ) = viewModelScope.launch(Dispatchers.IO) {
        if (title.isBlank()) return@launch
        val preferences = container.settingsRepository.current()
        val outcome = container.taskRepository.create(
            draft = TaskDraft(
                title = title.trim(),
                date = date,
                startTime = start,
                endTime = end,
                categoryName = categoryName,
                priority = priority,
                reminderMinutes = reminderMinutes,
                notes = notes?.takeIf { it.isNotBlank() },
            ),
            now = container.now(),
            defaultReminderMinutes = preferences.defaultReminderMinutes,
        )
        _state.value = _state.value.copy(
            conflicts = if (preferences.conflictWarningsEnabled) outcome.conflicts else emptyList(),
            message = outcome.conflicts.firstOrNull()?.describe(),
        )
        refresh()
    }

    fun update(task: Task) = viewModelScope.launch(Dispatchers.IO) {
        container.taskRepository.save(task, container.now())
        refresh()
    }

    fun changeStatus(taskId: Long, status: TaskStatus) = viewModelScope.launch(Dispatchers.IO) {
        val result = container.taskRepository.changeStatus(taskId, status, container.now())
        _state.value = _state.value.copy(message = result.exceptionOrNull()?.message)
        refresh()
    }

    fun setProgress(taskId: Long, percent: Int) = viewModelScope.launch(Dispatchers.IO) {
        container.taskRepository.setProgress(taskId, percent, container.now())
        refresh()
    }

    fun delete(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        container.taskRepository.delete(taskId)
        refresh()
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, conflicts = emptyList())
    }
}
