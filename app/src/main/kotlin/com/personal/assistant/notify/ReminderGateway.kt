package com.personal.assistant.notify

import com.personal.assistant.core.model.Task

/**
 * The repository's view of the alarm layer.
 *
 * Kept as an interface so task logic can be exercised without the Android alarm manager, and so a
 * future change in how alarms are delivered (a foreground service, a different exact-alarm strategy)
 * does not reach into the repository.
 */
interface ReminderGateway {

    /** Replaces every pending alarm for this task with the ones it needs now. */
    suspend fun reschedule(task: Task)

    suspend fun cancelFor(taskId: Long)

    /** Rebuilds every alarm from the database, after a reboot or a time-zone change. */
    suspend fun rescheduleAll()

    /** Re-arms the daily summary and weekly report jobs from the current settings. */
    suspend fun rescheduleSummaries()
}

/** Used in tests and before the alarm layer is wired up. */
object NoOpReminderGateway : ReminderGateway {
    override suspend fun reschedule(task: Task) = Unit
    override suspend fun cancelFor(taskId: Long) = Unit
    override suspend fun rescheduleAll() = Unit
    override suspend fun rescheduleSummaries() = Unit
}
