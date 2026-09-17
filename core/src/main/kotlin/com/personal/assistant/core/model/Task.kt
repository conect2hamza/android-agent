package com.personal.assistant.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A single planned activity. The database is the source of truth for every field here; the
 * language model only ever proposes values, which are validated before they reach this type.
 */
data class Task(
    val id: Long = UNSAVED,
    val title: String,
    val description: String? = null,
    val date: LocalDate,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val durationMinutes: Int? = null,
    val priority: Priority = Priority.NORMAL,
    val categoryId: Long? = null,
    val status: TaskStatus = TaskStatus.PLANNED,
    val progress: Int = 0,
    val reminderMinutes: Int? = DEFAULT_REMINDER_MINUTES,
    val notes: String? = null,
    val recurrenceId: Long? = null,
    /** Set on generated occurrences of a recurring series; null for standalone tasks. */
    val seriesId: Long? = null,
    val trackingEnabled: Boolean = true,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val completedAt: LocalDateTime? = null,
) {
    init {
        require(progress in 0..100) { "progress must be 0..100 but was $progress" }
    }

    val startDateTime: LocalDateTime?
        get() = startTime?.let { LocalDateTime.of(date, it) }

    /**
     * End of the task. Falls back to start + duration, so a task created as "two hours tomorrow
     * from 4pm" still has a completion-check time even though no explicit end was given.
     */
    val endDateTime: LocalDateTime?
        get() = when {
            endTime != null -> LocalDateTime.of(date, endTime)
            startTime != null && durationMinutes != null ->
                LocalDateTime.of(date, startTime).plusMinutes(durationMinutes.toLong())
            else -> null
        }

    val plannedDuration: Duration?
        get() {
            val start = startDateTime ?: return durationMinutes?.let { Duration.ofMinutes(it.toLong()) }
            val end = endDateTime ?: return durationMinutes?.let { Duration.ofMinutes(it.toLong()) }
            val span = Duration.between(start, end)
            return if (span.isNegative) null else span
        }

    val plannedMinutes: Int
        get() = plannedDuration?.toMinutes()?.toInt() ?: 0

    /** When the reminder notification should fire, or null if the task has no specific time. */
    val reminderAt: LocalDateTime?
        get() {
            val start = startDateTime ?: return null
            val lead = reminderMinutes ?: return null
            return start.minusMinutes(lead.toLong())
        }

    val isAllDay: Boolean get() = startTime == null

    companion object {
        const val DEFAULT_REMINDER_MINUTES: Int = 30

        /** Offered in settings and on the reminder picker. */
        val REMINDER_CHOICES: List<Int> = listOf(5, 10, 15, 30, 60, 120)
    }
}
