package com.personal.assistant.core.domain

import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.model.ActivityEvent
import com.personal.assistant.core.model.ActivityLog
import com.personal.assistant.core.model.Category
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.core.report.CategorySlice
import com.personal.assistant.core.report.DailyReport
import com.personal.assistant.core.report.DayPoint
import com.personal.assistant.core.report.MonthlyReport
import com.personal.assistant.core.report.TaskTotals
import com.personal.assistant.core.report.TimelineEntry
import com.personal.assistant.core.report.WeeklyReport
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure aggregation over tasks and activity logs. No database access, no formatting, no side effects,
 * so every number in a report can be reproduced in a unit test.
 */
class ReportGenerator(
    private val categories: List<Category> = emptyList(),
) {

    fun daily(
        date: LocalDate,
        tasks: List<Task>,
        logs: List<ActivityLog>,
        now: LocalDateTime,
    ): DailyReport {
        val forDay = tasks.filter { it.date == date }
        val logsForDay = logs.filter { it.timestamp.toLocalDate() == date }
        return DailyReport(
            date = date,
            totals = totals(forDay, now),
            plannedMinutes = forDay.sumOf { it.plannedMinutes },
            trackedMinutes = trackedMinutes(forDay, logsForDay, now),
            categories = categorySlices(forDay, logsForDay, now),
            timeline = timeline(logsForDay, forDay),
        )
    }

    fun weekly(
        range: DateRange,
        tasks: List<Task>,
        logs: List<ActivityLog>,
        now: LocalDateTime,
    ): WeeklyReport {
        val inRange = tasks.filter { it.date in range }
        val logsInRange = logs.filter { it.timestamp.toLocalDate() in range }
        return WeeklyReport(
            range = range,
            totals = totals(inRange, now),
            plannedMinutes = inRange.sumOf { it.plannedMinutes },
            trackedMinutes = trackedMinutes(inRange, logsInRange, now),
            perDay = perDay(range, inRange, logsInRange, now),
            categories = categorySlices(inRange, logsInRange, now),
        )
    }

    fun monthly(
        range: DateRange,
        tasks: List<Task>,
        logs: List<ActivityLog>,
        now: LocalDateTime,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): MonthlyReport {
        val inRange = tasks.filter { it.date in range }
        val logsInRange = logs.filter { it.timestamp.toLocalDate() in range }
        val days = perDay(range, inRange, logsInRange, now)

        val weeks = mutableListOf<Pair<DateRange, Double>>()
        var cursor = range.start
        while (!cursor.isAfter(range.end)) {
            val week = DateRange.week(cursor, firstDayOfWeek)
            // Clip the first and last weeks to the month being reported on.
            val clipped = DateRange(
                start = maxOf(week.start, range.start),
                end = minOf(week.end, range.end),
            )
            val weekTasks = inRange.filter { it.date in clipped }
            weeks += clipped to totals(weekTasks, now).completionRate
            cursor = week.end.plusDays(1)
        }

        return MonthlyReport(
            range = range,
            totals = totals(inRange, now),
            plannedMinutes = inRange.sumOf { it.plannedMinutes },
            trackedMinutes = trackedMinutes(inRange, logsInRange, now),
            perDay = days,
            categories = categorySlices(inRange, logsInRange, now),
            mostActiveDays = days
                .filter { it.totals.total > 0 }
                .sortedWith(
                    compareByDescending<DayPoint> { it.totals.completed }
                        .thenByDescending { it.trackedMinutes },
                )
                .take(5),
            weeklyTrend = weeks,
        )
    }

    // ----------------------------------------------------------------- internals

    /**
     * Counts statuses, applying the clock first: a task whose end time has passed without being
     * touched counts as missed in the report even though nothing has written that status to it yet.
     */
    fun totals(tasks: List<Task>, now: LocalDateTime): TaskTotals {
        var totals = TaskTotals(total = tasks.size)
        for (task in tasks) {
            when (TaskStateMachine.deriveForClock(task, now)) {
                TaskStatus.COMPLETED -> totals = totals.copy(completed = totals.completed + 1)
                TaskStatus.PARTIALLY_COMPLETED -> totals = totals.copy(partial = totals.partial + 1)
                TaskStatus.MISSED -> totals = totals.copy(missed = totals.missed + 1)
                TaskStatus.STARTED, TaskStatus.IN_PROGRESS ->
                    totals = totals.copy(inProgress = totals.inProgress + 1)
                TaskStatus.CANCELLED -> totals = totals.copy(cancelled = totals.cancelled + 1)
                TaskStatus.SKIPPED -> totals = totals.copy(skipped = totals.skipped + 1)
                TaskStatus.POSTPONED -> totals = totals.copy(postponed = totals.postponed + 1)
                TaskStatus.PLANNED, TaskStatus.UPCOMING -> totals = totals.copy(pending = totals.pending + 1)
            }
        }
        return totals
    }

    private fun perDay(
        range: DateRange,
        tasks: List<Task>,
        logs: List<ActivityLog>,
        now: LocalDateTime,
    ): List<DayPoint> = range.dates().map { date ->
        val forDay = tasks.filter { it.date == date }
        val logsForDay = logs.filter { it.timestamp.toLocalDate() == date }
        DayPoint(date, totals(forDay, now), trackedMinutes(forDay, logsForDay, now))
    }

    private fun trackedMinutes(tasks: List<Task>, logs: List<ActivityLog>, now: LocalDateTime): Int {
        val byTask = logs.groupBy { it.taskId }
        var total = 0
        for (task in tasks) {
            val taskLogs = byTask[task.id] ?: continue
            total += TimeTracker.tracked(taskLogs, now).activeMinutes
        }
        // Logs explicitly carrying a duration (a manually entered session) are added as given.
        total += logs.filter { it.durationMinutes != null && it.event == ActivityEvent.NOTE_ADDED }
            .sumOf { it.durationMinutes ?: 0 }
        return total
    }

    private fun categorySlices(
        tasks: List<Task>,
        logs: List<ActivityLog>,
        now: LocalDateTime,
    ): List<CategorySlice> {
        val names = categories.associateBy({ it.id }, { it.name })
        val byTask = logs.groupBy { it.taskId }
        return tasks
            .groupBy { it.categoryId }
            .map { (categoryId, group) ->
                CategorySlice(
                    categoryId = categoryId,
                    label = names[categoryId] ?: "Uncategorised",
                    taskCount = group.size,
                    minutes = group.sumOf { task ->
                        val taskLogs = byTask[task.id]
                        if (taskLogs.isNullOrEmpty()) {
                            task.plannedMinutes
                        } else {
                            TimeTracker.tracked(taskLogs, now).activeMinutes
                        }
                    },
                )
            }
            .sortedByDescending { it.minutes }
    }

    private fun timeline(logs: List<ActivityLog>, tasks: List<Task>): List<TimelineEntry> {
        val titles = tasks.associateBy({ it.id }, { it.title })
        return logs
            .filter { it.event != ActivityEvent.REMINDED }
            .sortedBy { it.timestamp }
            .map { log ->
                val title = titles[log.taskId] ?: "Task"
                val label = when (log.event) {
                    ActivityEvent.STARTED -> "$title started"
                    ActivityEvent.PAUSED -> "$title paused"
                    ActivityEvent.RESUMED -> "$title resumed"
                    ActivityEvent.COMPLETED -> "$title completed"
                    ActivityEvent.PARTIALLY_COMPLETED -> "$title partially completed"
                    ActivityEvent.PROGRESS_UPDATED -> "$title progress updated"
                    ActivityEvent.MISSED -> "$title missed"
                    ActivityEvent.SKIPPED -> "$title skipped"
                    ActivityEvent.CANCELLED -> "$title cancelled"
                    ActivityEvent.POSTPONED -> "$title postponed"
                    ActivityEvent.RESCHEDULED -> "$title rescheduled"
                    ActivityEvent.CREATED -> "$title created"
                    ActivityEvent.NOTE_ADDED -> log.notes ?: "$title note added"
                    ActivityEvent.REMINDED -> "$title reminder"
                }
                TimelineEntry(log.timestamp, label, log.taskId)
            }
    }
}
