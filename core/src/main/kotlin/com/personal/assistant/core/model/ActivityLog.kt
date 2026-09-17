package com.personal.assistant.core.model

import java.time.LocalDateTime

enum class ActivityEvent {
    CREATED,
    RESCHEDULED,
    REMINDED,
    STARTED,
    PAUSED,
    RESUMED,
    PROGRESS_UPDATED,
    COMPLETED,
    PARTIALLY_COMPLETED,
    SKIPPED,
    MISSED,
    CANCELLED,
    POSTPONED,
    NOTE_ADDED,
}

/**
 * Append-only record of what actually happened, as opposed to what was planned. Reports and the
 * timeline read from here, never from the assistant's chat history.
 */
data class ActivityLog(
    val id: Long = UNSAVED,
    val taskId: Long?,
    val event: ActivityEvent,
    val timestamp: LocalDateTime,
    /** Active minutes attributable to this event; used to total real tracked time. */
    val durationMinutes: Int? = null,
    val notes: String? = null,
)
