package com.personal.assistant.core.domain

import com.personal.assistant.core.model.ActivityEvent
import com.personal.assistant.core.model.ActivityLog
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Planned versus actually tracked time for one task. */
data class TrackedTime(
    val activeMinutes: Int,
    val running: Boolean,
    val firstStart: LocalDateTime?,
    val lastEnd: LocalDateTime?,
)

/**
 * Derives real elapsed time from the activity log rather than storing a running total.
 *
 * Storing a counter would drift whenever the process is killed mid-task -- which, on a phone with an
 * aggressive battery manager, is the normal case. Replaying the log is cheap and cannot drift.
 */
object TimeTracker {

    fun tracked(logs: List<ActivityLog>, now: LocalDateTime): TrackedTime {
        val ordered = logs.sortedBy { it.timestamp }
        var total = 0L
        var openedAt: LocalDateTime? = null
        var firstStart: LocalDateTime? = null
        var lastEnd: LocalDateTime? = null

        for (log in ordered) {
            when (log.event) {
                ActivityEvent.STARTED, ActivityEvent.RESUMED -> {
                    if (openedAt == null) openedAt = log.timestamp
                    if (firstStart == null) firstStart = log.timestamp
                }

                ActivityEvent.PAUSED,
                ActivityEvent.COMPLETED,
                ActivityEvent.PARTIALLY_COMPLETED,
                ActivityEvent.CANCELLED,
                ActivityEvent.SKIPPED,
                ActivityEvent.MISSED,
                -> {
                    openedAt?.let { start ->
                        total += ChronoUnit.MINUTES.between(start, log.timestamp).coerceAtLeast(0)
                    }
                    openedAt = null
                    lastEnd = log.timestamp
                }

                else -> Unit
            }
        }

        val running = openedAt != null
        if (running) {
            total += ChronoUnit.MINUTES.between(openedAt, now).coerceAtLeast(0)
        }
        return TrackedTime(total.toInt(), running, firstStart, lastEnd)
    }

    fun format(minutes: Int): String {
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
            hours > 0 -> "${hours}h"
            else -> "${remainder}m"
        }
    }

    fun format(duration: Duration): String = format(duration.toMinutes().toInt())
}
