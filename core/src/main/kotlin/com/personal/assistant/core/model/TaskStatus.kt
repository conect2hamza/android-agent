package com.personal.assistant.core.model

/**
 * Task lifecycle from the specification.
 *
 * ```
 * PLANNED -> UPCOMING -> STARTED -> IN_PROGRESS -> COMPLETED
 * ```
 *
 * with CANCELLED / POSTPONED / SKIPPED / MISSED reachable from the active states.
 */
enum class TaskStatus {
    PLANNED,
    UPCOMING,
    STARTED,
    IN_PROGRESS,
    COMPLETED,
    PARTIALLY_COMPLETED,
    CANCELLED,
    POSTPONED,
    SKIPPED,
    MISSED;

    val isTerminal: Boolean
        get() = this == COMPLETED ||
            this == PARTIALLY_COMPLETED ||
            this == CANCELLED ||
            this == SKIPPED ||
            this == MISSED

    val isActive: Boolean
        get() = this == STARTED || this == IN_PROGRESS

    val countsAsDone: Boolean
        get() = this == COMPLETED

    companion object {
        fun fromNameOrNull(value: String?): TaskStatus? =
            value?.trim()?.uppercase()?.replace(' ', '_')?.let { name ->
                entries.firstOrNull { it.name == name }
            }
    }
}
