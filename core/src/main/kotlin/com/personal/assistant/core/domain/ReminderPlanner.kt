package com.personal.assistant.core.domain

import com.personal.assistant.core.model.Reminder
import com.personal.assistant.core.model.ReminderKind
import com.personal.assistant.core.model.Task
import java.time.LocalDateTime

/**
 * Works out which notifications a task needs and when.
 *
 * Producing the whole set up front, as plain data, keeps the Android alarm code dumb: it schedules
 * exactly what this returns and cancels whatever is no longer in the list. It also means the follow-up
 * behaviour the specification asks for -- lead-up reminder, "are you starting it?", a progress check,
 * "did you complete it?" -- is decided in testable code rather than spread across receivers.
 */
object ReminderPlanner {

    /** Only ask about progress on work long enough for the question to be meaningful. */
    const val PROGRESS_CHECK_THRESHOLD_MINUTES = 60

    /** Grace period after the planned end before asking whether it got done. */
    const val COMPLETION_GRACE_MINUTES = 5L

    fun plan(
        task: Task,
        now: LocalDateTime,
        progressChecksEnabled: Boolean = true,
    ): List<Reminder> {
        if (task.status.isTerminal) return emptyList()
        val start = task.startDateTime ?: return emptyList()
        val reminders = mutableListOf<Reminder>()

        task.reminderAt?.let { at ->
            if (at.isAfter(now)) reminders += reminder(task, ReminderKind.LEAD_UP, at)
        }

        if (start.isAfter(now)) reminders += reminder(task, ReminderKind.START, start)

        val end = task.endDateTime
        if (progressChecksEnabled && end != null) {
            val planned = task.plannedMinutes
            if (planned >= PROGRESS_CHECK_THRESHOLD_MINUTES) {
                // Halfway through is the point where "how far along are you?" is worth asking.
                val midpoint = start.plusMinutes((planned / 2).toLong())
                if (midpoint.isAfter(now) && midpoint.isBefore(end)) {
                    reminders += reminder(task, ReminderKind.PROGRESS_CHECK, midpoint)
                }
            }
        }

        if (end != null) {
            val checkAt = end.plusMinutes(COMPLETION_GRACE_MINUTES)
            if (checkAt.isAfter(now)) reminders += reminder(task, ReminderKind.COMPLETION_CHECK, checkAt)
        }

        return reminders
    }

    private fun reminder(task: Task, kind: ReminderKind, at: LocalDateTime) = Reminder(
        taskId = task.id,
        kind = kind,
        triggerAt = at,
        requestCode = requestCode(task.id, kind),
    )

    /**
     * Deterministic PendingIntent request code, so rescheduling a task replaces its alarms instead of
     * stacking duplicates. Derived from the row id and the reminder kind; the modulus keeps it inside
     * Int range for the very large ids an import could produce.
     */
    fun requestCode(taskId: Long, kind: ReminderKind): Int {
        val base = (taskId % 100_000_000L).toInt()
        return base * ReminderKind.entries.size + kind.ordinal
    }
}
