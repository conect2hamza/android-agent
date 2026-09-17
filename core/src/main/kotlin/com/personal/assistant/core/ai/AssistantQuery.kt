package com.personal.assistant.core.ai

import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** An inclusive range of dates. */
data class DateRange(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "range end $end precedes start $start" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    val days: Int get() = (end.toEpochDay() - start.toEpochDay()).toInt() + 1

    fun dates(): List<LocalDate> = (0 until days).map { start.plusDays(it.toLong()) }

    companion object {
        fun single(date: LocalDate) = DateRange(date, date)

        /** [firstDayOfWeek] comes from user settings, so a Sunday-first week is honoured. */
        fun week(date: LocalDate, firstDayOfWeek: java.time.DayOfWeek): DateRange {
            val start = date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            return DateRange(start, start.plusDays(6))
        }

        fun month(date: LocalDate): DateRange =
            DateRange(date.withDayOfMonth(1), date.with(TemporalAdjusters.lastDayOfMonth()))
    }
}

enum class QueryKind {
    /** "What is my plan today?" */
    PLAN,

    /** "What did I complete today?" */
    COMPLETED,

    /** "What did I miss yesterday?" */
    MISSED,

    /** "Show my upcoming tasks." */
    UPCOMING,

    /** "How much time did I spend studying this week?" */
    TIME_SPENT,

    /** Free-text search over titles, notes and logged activity. */
    SEARCH,

    DAILY_REPORT,
    WEEKLY_REPORT,
    MONTHLY_REPORT,
}

data class AssistantQuery(
    val kind: QueryKind,
    val range: DateRange,
    /** Extra words to match against task titles/categories, e.g. "website", "studying". */
    val keywords: List<String> = emptyList(),
)
