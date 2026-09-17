package com.personal.assistant.core.model

import java.time.LocalDateTime

enum class ReminderKind {
    /** "You have X scheduled at 4:00 PM." -- fires `reminderMinutes` before the start. */
    LEAD_UP,

    /** "X is starting now. Are you starting it?" */
    START,

    /** "How much progress have you made?" -- only while a task is active. */
    PROGRESS_CHECK,

    /** "Did you complete it?" -- fires after the planned end time. */
    COMPLETION_CHECK,

    /** Follow-up on something that was never marked done. */
    MISSED_FOLLOW_UP,

    DAILY_SUMMARY,
    WEEKLY_REPORT,
}

data class Reminder(
    val id: Long = UNSAVED,
    val taskId: Long?,
    val kind: ReminderKind,
    val triggerAt: LocalDateTime,
    val fired: Boolean = false,
    /** Stable PendingIntent request code, so an alarm can be replaced or cancelled. */
    val requestCode: Int,
)
