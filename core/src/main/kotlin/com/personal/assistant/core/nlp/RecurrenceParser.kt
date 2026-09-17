package com.personal.assistant.core.nlp

import com.personal.assistant.core.model.RecurrenceFrequency
import java.time.DayOfWeek

/** A repeat rule found in the input, before it is anchored to a start date. */
data class RecurrenceDraft(
    val frequency: RecurrenceFrequency,
    val interval: Int = 1,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null,
    val spans: List<Span>,
)

object RecurrenceParser {

    private val WEEK_NOUNS = setOf("week", "weeks", "hafta", "hafte", "haftay", "ہفتے", "ہفتہ")
    private val MONTH_NOUNS = setOf("month", "months", "maheena", "mahine", "mahiney", "مہینے", "ماہ")
    private val DAY_NOUNS = setOf("day", "days", "din", "روز", "دن")
    private val AND_WORDS = setOf("and", "&", "aur", "،", ",", "اور")

    /**
     * Must run before [DateResolver] so that "every Monday" becomes a series rather than a one-off
     * task on the next Monday.
     *
     * "har hafta" is read as *every week*, not every Saturday, even though "hafta" alone is Saturday
     * elsewhere in the lexicon -- after "har" the week reading is overwhelmingly the intended one.
     */
    fun find(tokens: List<Token>, tracker: SpanTracker): RecurrenceDraft? {
        for (i in tokens.indices) {
            val token = tokens[i]
            if (!tracker.isFree(token.span)) continue

            if (token.text in Lexicon.DAILY_WORDS) {
                if (tracker.claim(token.span)) {
                    return RecurrenceDraft(RecurrenceFrequency.DAILY, spans = listOf(token.span))
                }
            }
            if (token.text in Lexicon.WEEKLY_WORDS && token.text !in Lexicon.WEEKDAYS) {
                if (tracker.claim(token.span)) {
                    return RecurrenceDraft(RecurrenceFrequency.WEEKLY, spans = listOf(token.span))
                }
            }
            if (token.text in Lexicon.MONTHLY_WORDS) {
                if (tracker.claim(token.span)) {
                    return RecurrenceDraft(RecurrenceFrequency.MONTHLY, spans = listOf(token.span))
                }
            }
            if (token.text !in Lexicon.EVERY_WORDS) continue

            // "every 2 weeks", "har 3 din"
            var cursor = i + 1
            var interval = 1
            Lexicon.numberOf(tokens.getOrNull(cursor)?.text ?: "")?.let { value ->
                if (value in 1..52) {
                    interval = value
                    cursor++
                }
            }

            val unit = tokens.getOrNull(cursor) ?: continue

            // "every weekday" / "har kaam ke din"
            if (unit.text in Lexicon.WEEKDAY_SET_WORDS) {
                val span = Span(token.start, unit.end)
                if (tracker.claim(span)) {
                    return RecurrenceDraft(
                        RecurrenceFrequency.WEEKLY,
                        interval = interval,
                        daysOfWeek = DateResolver.WEEKDAYS_MON_TO_FRI,
                        spans = listOf(span),
                    )
                }
            }

            if (unit.text in DAY_NOUNS || unit.text in Lexicon.DAILY_WORDS) {
                val span = Span(token.start, unit.end)
                if (tracker.claim(span)) {
                    return RecurrenceDraft(RecurrenceFrequency.DAILY, interval, spans = listOf(span))
                }
            }

            if (unit.text in WEEK_NOUNS) {
                val span = Span(token.start, unit.end)
                if (tracker.claim(span)) {
                    return RecurrenceDraft(RecurrenceFrequency.WEEKLY, interval, spans = listOf(span))
                }
            }

            if (unit.text in MONTH_NOUNS) {
                val span = Span(token.start, unit.end)
                if (tracker.claim(span)) {
                    return RecurrenceDraft(RecurrenceFrequency.MONTHLY, interval, spans = listOf(span))
                }
            }

            // "every Monday", "every Monday and Wednesday", "har peer aur budh"
            val days = linkedSetOf<DayOfWeek>()
            var end = unit.end
            var scan = cursor
            while (scan < tokens.size) {
                val candidate = tokens[scan]
                val day = Lexicon.WEEKDAYS[candidate.text]
                if (day != null) {
                    days += day
                    end = candidate.end
                    scan++
                    val connector = tokens.getOrNull(scan)
                    if (connector != null && connector.text in AND_WORDS) {
                        scan++
                        continue
                    }
                }
                break
            }
            if (days.isNotEmpty()) {
                val span = Span(token.start, end)
                if (tracker.claim(span)) {
                    return RecurrenceDraft(
                        RecurrenceFrequency.WEEKLY,
                        interval = interval,
                        daysOfWeek = days,
                        spans = listOf(span),
                    )
                }
            }
        }
        return null
    }
}
