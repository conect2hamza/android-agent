package com.personal.assistant.core.model

import java.time.DayOfWeek
import java.time.LocalDate

enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY }

/**
 * A repeat rule. Deliberately narrow: daily, weekly on chosen weekdays, and monthly on a day of
 * month. Anything the user phrases more loosely ("I usually study in the evening") is stored as a
 * [Memory] preference instead, because a vague habit should not silently create alarms.
 */
data class Recurrence(
    val id: Long = UNSAVED,
    val frequency: RecurrenceFrequency,
    /** Every N days/weeks/months. */
    val interval: Int = 1,
    /** Used by [RecurrenceFrequency.WEEKLY]; empty means "same weekday as the start date". */
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    /** Used by [RecurrenceFrequency.MONTHLY]; null means "same day of month as the start date". */
    val dayOfMonth: Int? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val occurrenceLimit: Int? = null,
) {
    init {
        require(interval >= 1) { "interval must be >= 1 but was $interval" }
        require(dayOfMonth == null || dayOfMonth in 1..31) { "dayOfMonth out of range: $dayOfMonth" }
    }

    fun describe(): String = buildString {
        when (frequency) {
            RecurrenceFrequency.DAILY ->
                append(if (interval == 1) "Every day" else "Every $interval days")

            RecurrenceFrequency.WEEKLY -> {
                append(if (interval == 1) "Every week" else "Every $interval weeks")
                if (daysOfWeek.isNotEmpty()) {
                    append(" on ")
                    append(
                        daysOfWeek.sortedBy { it.value }.joinToString(", ") { day ->
                            day.name.lowercase().replaceFirstChar { it.uppercase() }
                        },
                    )
                }
            }

            RecurrenceFrequency.MONTHLY -> {
                append(if (interval == 1) "Every month" else "Every $interval months")
                dayOfMonth?.let { append(" on day $it") }
            }
        }
        endDate?.let { append(" until $it") }
    }
}
