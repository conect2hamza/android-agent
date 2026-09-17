package com.personal.assistant.core.domain

import com.personal.assistant.core.model.Recurrence
import com.personal.assistant.core.model.RecurrenceFrequency
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Two tasks that share time on the calendar. */
data class Conflict(
    val first: Task,
    val second: Task,
    val overlapMinutes: Int,
) {
    fun describe(): String {
        val hours = overlapMinutes / 60
        val minutes = overlapMinutes % 60
        val amount = when {
            hours > 0 && minutes > 0 -> "$hours h $minutes min"
            hours > 0 -> if (hours == 1) "one hour" else "$hours hours"
            else -> "$minutes minutes"
        }
        return "\"${first.title}\" and \"${second.title}\" overlap by $amount."
    }
}

object SchedulingEngine {

    /**
     * Finds every pair of timed, non-cancelled tasks on the same day whose intervals overlap.
     *
     * All-day tasks are ignored: without a start and end they cannot be said to clash with anything,
     * and warning about them would train the user to dismiss the warning.
     */
    fun detectConflicts(tasks: List<Task>): List<Conflict> {
        val timed = tasks
            .filter { !it.status.isTerminal || it.status == TaskStatus.MISSED }
            .filter { it.status != TaskStatus.CANCELLED && it.status != TaskStatus.SKIPPED }
            .mapNotNull { task ->
                val start = task.startDateTime ?: return@mapNotNull null
                val end = task.endDateTime ?: return@mapNotNull null
                if (!end.isAfter(start)) return@mapNotNull null
                Triple(task, start, end)
            }
            .sortedBy { it.second }

        val conflicts = mutableListOf<Conflict>()
        for (i in timed.indices) {
            for (j in i + 1 until timed.size) {
                val (firstTask, firstStart, firstEnd) = timed[i]
                val (secondTask, secondStart, secondEnd) = timed[j]
                if (!secondStart.isBefore(firstEnd)) break // sorted, so nothing later can overlap
                val overlapStart = maxOf(firstStart, secondStart)
                val overlapEnd = minOf(firstEnd, secondEnd)
                val minutes = ChronoUnit.MINUTES.between(overlapStart, overlapEnd).toInt()
                if (minutes > 0) conflicts += Conflict(firstTask, secondTask, minutes)
            }
        }
        return conflicts
    }

    /** Conflicts involving one specific candidate, used before saving a newly created task. */
    fun conflictsFor(candidate: Task, sameDay: List<Task>): List<Conflict> =
        detectConflicts(sameDay.filter { it.id != candidate.id } + candidate)
            .filter { it.first.id == candidate.id || it.second.id == candidate.id }

    /**
     * Expands a repeat rule into concrete dates within [window].
     *
     * Occurrences are generated on demand for the window being displayed or scheduled rather than
     * written out years ahead: a recurring task should not cost a thousand database rows, and the
     * alarm layer only ever needs the next few.
     */
    fun occurrences(
        recurrence: Recurrence,
        window: ClosedRange<LocalDate>,
        limit: Int = 366,
    ): List<LocalDate> {
        val result = mutableListOf<LocalDate>()
        val hardEnd = listOfNotNull(window.endInclusive, recurrence.endDate).min()
        val from = maxOf(window.start, recurrence.startDate)
        if (from.isAfter(hardEnd)) return emptyList()

        when (recurrence.frequency) {
            RecurrenceFrequency.DAILY -> {
                // Step from the series start so "every 3 days" stays in phase with its anchor.
                val offset = ChronoUnit.DAYS.between(recurrence.startDate, from)
                val alignment = ((offset % recurrence.interval) + recurrence.interval) % recurrence.interval
                var cursor = if (alignment == 0L) from else from.plusDays(recurrence.interval - alignment)
                while (!cursor.isAfter(hardEnd) && result.size < limit) {
                    result += cursor
                    cursor = cursor.plusDays(recurrence.interval.toLong())
                }
            }

            RecurrenceFrequency.WEEKLY -> {
                val days = recurrence.daysOfWeek.ifEmpty { setOf(recurrence.startDate.dayOfWeek) }
                var cursor = from
                while (!cursor.isAfter(hardEnd) && result.size < limit) {
                    if (cursor.dayOfWeek in days) {
                        val weeks = ChronoUnit.WEEKS.between(
                            recurrence.startDate.with(java.time.DayOfWeek.MONDAY),
                            cursor.with(java.time.DayOfWeek.MONDAY),
                        )
                        if (weeks % recurrence.interval == 0L) result += cursor
                    }
                    cursor = cursor.plusDays(1)
                }
            }

            RecurrenceFrequency.MONTHLY -> {
                val dayOfMonth = recurrence.dayOfMonth ?: recurrence.startDate.dayOfMonth
                var monthCursor = recurrence.startDate.withDayOfMonth(1)
                while (!monthCursor.isAfter(hardEnd) && result.size < limit) {
                    val months = ChronoUnit.MONTHS.between(recurrence.startDate.withDayOfMonth(1), monthCursor)
                    if (months >= 0 && months % recurrence.interval == 0L) {
                        // Clamp so "the 31st" still fires in February instead of being skipped.
                        val day = minOf(dayOfMonth, monthCursor.lengthOfMonth())
                        val candidate = monthCursor.withDayOfMonth(day)
                        if (!candidate.isBefore(from) && !candidate.isAfter(hardEnd)) result += candidate
                    }
                    monthCursor = monthCursor.plusMonths(1)
                }
            }
        }

        val capped = recurrence.occurrenceLimit?.let { max ->
            val alreadyPast = when (recurrence.frequency) {
                RecurrenceFrequency.DAILY ->
                    ChronoUnit.DAYS.between(recurrence.startDate, from) / recurrence.interval
                else -> 0L
            }
            (max - alreadyPast).coerceAtLeast(0L).toInt()
        } ?: result.size
        return result.take(minOf(limit, capped))
    }

    /** Finds a free slot of [minutes] on [date], respecting the user's working window. */
    fun firstFreeSlot(
        date: LocalDate,
        minutes: Int,
        existing: List<Task>,
        dayStart: java.time.LocalTime = java.time.LocalTime.of(9, 0),
        dayEnd: java.time.LocalTime = java.time.LocalTime.of(22, 0),
    ): LocalDateTime? {
        val busy = existing
            .filter { it.date == date && !it.status.isTerminal }
            .mapNotNull { task ->
                val start = task.startDateTime ?: return@mapNotNull null
                val end = task.endDateTime ?: return@mapNotNull null
                start to end
            }
            .sortedBy { it.first }

        var cursor = LocalDateTime.of(date, dayStart)
        val limit = LocalDateTime.of(date, dayEnd)
        for ((start, end) in busy) {
            if (ChronoUnit.MINUTES.between(cursor, minOf(start, limit)) >= minutes) return cursor
            if (end.isAfter(cursor)) cursor = end
        }
        return cursor.takeIf { ChronoUnit.MINUTES.between(it, limit) >= minutes }
    }
}
