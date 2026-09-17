package com.personal.assistant.core.domain

import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import java.time.LocalDateTime

/**
 * The single place that decides whether a status change is legal.
 *
 * Both the chat layer and the notification action buttons funnel through here, so a "Complete" tap
 * on a stale notification cannot resurrect a cancelled task.
 */
object TaskStateMachine {

    private val allowed: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.PLANNED to setOf(
            TaskStatus.UPCOMING, TaskStatus.STARTED, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED,
            TaskStatus.CANCELLED, TaskStatus.POSTPONED, TaskStatus.SKIPPED, TaskStatus.MISSED,
        ),
        TaskStatus.UPCOMING to setOf(
            TaskStatus.STARTED, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED, TaskStatus.CANCELLED,
            TaskStatus.POSTPONED, TaskStatus.SKIPPED, TaskStatus.MISSED, TaskStatus.PLANNED,
        ),
        TaskStatus.STARTED to setOf(
            TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED, TaskStatus.PARTIALLY_COMPLETED,
            TaskStatus.CANCELLED, TaskStatus.POSTPONED, TaskStatus.MISSED,
        ),
        TaskStatus.IN_PROGRESS to setOf(
            TaskStatus.STARTED, TaskStatus.COMPLETED, TaskStatus.PARTIALLY_COMPLETED,
            TaskStatus.CANCELLED, TaskStatus.POSTPONED, TaskStatus.MISSED,
        ),
        // Terminal states can only be reopened deliberately, back to a planning state.
        TaskStatus.COMPLETED to setOf(TaskStatus.IN_PROGRESS, TaskStatus.PLANNED),
        TaskStatus.PARTIALLY_COMPLETED to setOf(
            TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED, TaskStatus.PLANNED, TaskStatus.POSTPONED,
        ),
        TaskStatus.CANCELLED to setOf(TaskStatus.PLANNED),
        TaskStatus.POSTPONED to setOf(TaskStatus.PLANNED, TaskStatus.UPCOMING, TaskStatus.CANCELLED),
        TaskStatus.SKIPPED to setOf(TaskStatus.PLANNED),
        TaskStatus.MISSED to setOf(TaskStatus.PLANNED, TaskStatus.POSTPONED, TaskStatus.COMPLETED, TaskStatus.SKIPPED),
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean =
        from == to || allowed[from]?.contains(to) == true

    /**
     * Applies a status change, keeping the derived fields consistent: completing a task fills in
     * `completedAt` and pins progress at 100, reopening one clears it.
     */
    fun apply(task: Task, to: TaskStatus, now: LocalDateTime): Result<Task> {
        if (!canTransition(task.status, to)) {
            return Result.failure(
                IllegalStateException("Cannot move \"${task.title}\" from ${task.status} to $to"),
            )
        }
        val progress = when (to) {
            TaskStatus.COMPLETED -> 100
            TaskStatus.PLANNED, TaskStatus.UPCOMING -> 0
            TaskStatus.STARTED -> maxOf(task.progress, 0)
            else -> task.progress
        }
        return Result.success(
            task.copy(
                status = to,
                progress = progress,
                completedAt = if (to == TaskStatus.COMPLETED || to == TaskStatus.PARTIALLY_COMPLETED) now else null,
                updatedAt = now,
            ),
        )
    }

    /**
     * Status implied purely by the clock, used by the alarm receivers and by the dashboard so an
     * untouched task does not sit at PLANNED forever.
     *
     * Only ever moves a task *forward* into UPCOMING or MISSED; anything the user has touched is left
     * alone, because the user's own action outranks the clock.
     */
    fun deriveForClock(task: Task, now: LocalDateTime): TaskStatus {
        if (task.status.isTerminal || task.status.isActive) return task.status
        val start = task.startDateTime
        val end = task.endDateTime

        if (end != null && now.isAfter(end)) return TaskStatus.MISSED
        // An all-day task is only missed once the day itself is over.
        if (end == null && start == null && now.toLocalDate().isAfter(task.date)) return TaskStatus.MISSED
        if (start != null && now.isAfter(start) && end == null) {
            // No end time: give it the rest of the day before calling it missed.
            if (now.toLocalDate().isAfter(task.date)) return TaskStatus.MISSED
        }
        if (start != null) {
            val lead = (task.reminderMinutes ?: Task.DEFAULT_REMINDER_MINUTES).toLong()
            if (!now.isBefore(start.minusMinutes(lead))) return TaskStatus.UPCOMING
        }
        return task.status
    }

    /** Progress alone decides whether "did you finish?" is answered as complete or partial. */
    fun statusForProgress(progress: Int): TaskStatus = when {
        progress >= 100 -> TaskStatus.COMPLETED
        progress > 0 -> TaskStatus.IN_PROGRESS
        else -> TaskStatus.STARTED
    }
}
