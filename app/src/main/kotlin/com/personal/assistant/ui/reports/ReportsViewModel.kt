package com.personal.assistant.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.assistant.AppContainer
import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.domain.ReportGenerator
import com.personal.assistant.core.report.DailyReport
import com.personal.assistant.core.report.MonthlyReport
import com.personal.assistant.core.report.WeeklyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class ReportPeriod { DAILY, WEEKLY, MONTHLY }

data class ReportsUiState(
    val period: ReportPeriod = ReportPeriod.DAILY,
    val anchor: LocalDate = LocalDate.now(),
    val daily: DailyReport? = null,
    val weekly: WeeklyReport? = null,
    val monthly: MonthlyReport? = null,
    val loading: Boolean = true,
)

class ReportsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ReportsUiState(anchor = container.now().toLocalDate()))
    val state: StateFlow<ReportsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun setPeriod(period: ReportPeriod) {
        _state.value = _state.value.copy(period = period)
        load()
    }

    fun shift(steps: Long) {
        val current = _state.value
        val anchor = when (current.period) {
            ReportPeriod.DAILY -> current.anchor.plusDays(steps)
            ReportPeriod.WEEKLY -> current.anchor.plusWeeks(steps)
            ReportPeriod.MONTHLY -> current.anchor.plusMonths(steps)
        }
        _state.value = current.copy(anchor = anchor)
        load()
    }

    /**
     * Reports are computed when the screen asks for them, not kept up to date in the background.
     * Nothing here is worth waking the device for, and the numbers are cheap to derive on demand.
     */
    fun load() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val now = container.now()
            val preferences = container.settingsRepository.current()
            val generator = ReportGenerator(container.taskRepository.categories())
            val current = _state.value

            when (current.period) {
                ReportPeriod.DAILY -> {
                    val range = DateRange.single(current.anchor)
                    _state.value = current.copy(
                        daily = generator.daily(
                            current.anchor,
                            container.taskRepository.range(range),
                            container.taskRepository.logsInRange(range),
                            now,
                        ),
                        loading = false,
                    )
                }

                ReportPeriod.WEEKLY -> {
                    val range = DateRange.week(current.anchor, preferences.firstDayOfWeek)
                    _state.value = current.copy(
                        weekly = generator.weekly(
                            range,
                            container.taskRepository.range(range),
                            container.taskRepository.logsInRange(range),
                            now,
                        ),
                        loading = false,
                    )
                }

                ReportPeriod.MONTHLY -> {
                    val range = DateRange.month(current.anchor)
                    _state.value = current.copy(
                        monthly = generator.monthly(
                            range,
                            container.taskRepository.range(range),
                            container.taskRepository.logsInRange(range),
                            now,
                            preferences.firstDayOfWeek,
                        ),
                        loading = false,
                    )
                }
            }
        }
    }
}
