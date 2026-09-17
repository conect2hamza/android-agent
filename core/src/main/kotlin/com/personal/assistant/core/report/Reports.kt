package com.personal.assistant.core.report

import com.personal.assistant.core.ai.DateRange
import java.time.LocalDate

/** Status counts for one slice of time. Everything else in a report is derived from these. */
data class TaskTotals(
    val total: Int = 0,
    val completed: Int = 0,
    val partial: Int = 0,
    val missed: Int = 0,
    val inProgress: Int = 0,
    val pending: Int = 0,
    val cancelled: Int = 0,
    val skipped: Int = 0,
    val postponed: Int = 0,
) {
    /**
     * Cancelled and skipped work is excluded from the denominator on purpose: a task the user
     * deliberately called off is not a failure to complete, and counting it as one makes the number
     * useless as feedback.
     */
    val completionRate: Double
        get() {
            val considered = total - cancelled - skipped
            if (considered <= 0) return 0.0
            return (completed + partial * 0.5) / considered * 100.0
        }

    operator fun plus(other: TaskTotals) = TaskTotals(
        total = total + other.total,
        completed = completed + other.completed,
        partial = partial + other.partial,
        missed = missed + other.missed,
        inProgress = inProgress + other.inProgress,
        pending = pending + other.pending,
        cancelled = cancelled + other.cancelled,
        skipped = skipped + other.skipped,
        postponed = postponed + other.postponed,
    )
}

data class CategorySlice(val categoryId: Long?, val label: String, val taskCount: Int, val minutes: Int)

data class TimelineEntry(val at: java.time.LocalDateTime, val label: String, val taskId: Long?)

data class DailyReport(
    val date: LocalDate,
    val totals: TaskTotals,
    val plannedMinutes: Int,
    val trackedMinutes: Int,
    val categories: List<CategorySlice>,
    val timeline: List<TimelineEntry>,
)

data class DayPoint(val date: LocalDate, val totals: TaskTotals, val trackedMinutes: Int)

data class WeeklyReport(
    val range: DateRange,
    val totals: TaskTotals,
    val plannedMinutes: Int,
    val trackedMinutes: Int,
    val perDay: List<DayPoint>,
    val categories: List<CategorySlice>,
)

data class MonthlyReport(
    val range: DateRange,
    val totals: TaskTotals,
    val plannedMinutes: Int,
    val trackedMinutes: Int,
    val perDay: List<DayPoint>,
    val categories: List<CategorySlice>,
    /** Highest-completion days first, for the "most active days" section. */
    val mostActiveDays: List<DayPoint>,
    /** Completion rate per week of the month, for the trend line. */
    val weeklyTrend: List<Pair<DateRange, Double>>,
)
