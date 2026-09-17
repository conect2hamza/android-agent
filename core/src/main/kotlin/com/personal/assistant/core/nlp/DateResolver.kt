package com.personal.assistant.core.nlp

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * A calendar date found in the input.
 *
 * [ambiguousDirection] is set for Urdu words that mean both a day forward and a day back -- "kal" is
 * equally "tomorrow" and "yesterday", and "parson" both directions two days out. The resolver picks
 * the direction from the sentence's intent (asking about the past looks backwards, planning looks
 * forwards) and marks the result so the assistant can confirm the date it chose.
 */
data class DateMatch(
    val date: LocalDate,
    val spans: List<Span>,
    val ambiguousDirection: Boolean = false,
)

object DateResolver {

    private val NEXT_WORDS = setOf("next", "coming", "agle", "agley", "اگلے", "آنے والے")
    private val LAST_WORDS = setOf("last", "previous", "pichle", "pichley", "گزشتہ", "پچھلے")
    private val IN_WORDS = setOf("in", "after", "baad", "بعد")
    private val DAY_WORDS = setOf("day", "days", "din", "dino", "دن")

    private val ISO_DATE = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val SLASH_DATE = Regex("""^(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2,4}))?$""")

    /**
     * @param reference "now" as the app sees it -- always injected so tests are deterministic and so
     *   a date never depends on the machine clock mid-calculation.
     * @param pastBias true when the surrounding sentence asks about something that already
     *   happened, which flips direction-ambiguous words backwards.
     */
    fun find(
        tokens: List<Token>,
        tracker: SpanTracker,
        reference: LocalDate,
        pastBias: Boolean = false,
    ): DateMatch? {
        multiWordRelative(tokens, tracker, reference, pastBias)?.let { return it }
        explicitCalendarDate(tokens, tracker, reference)?.let { return it }
        relativeInDays(tokens, tracker, reference, pastBias)?.let { return it }
        singleWordRelative(tokens, tracker, reference, pastBias)?.let { return it }
        weekday(tokens, tracker, reference, pastBias)?.let { return it }
        return null
    }

    private fun multiWordRelative(
        tokens: List<Token>,
        tracker: SpanTracker,
        reference: LocalDate,
        pastBias: Boolean,
    ): DateMatch? {
        for (size in 4 downTo 2) {
            for (i in tokens.indices) {
                val (phrase, span) = Tokenizer.phrase(tokens, i, size) ?: continue
                val relative = Lexicon.RELATIVE_DAY_PHRASES[phrase] ?: continue
                if (!tracker.claim(span)) continue
                val offset = direction(relative, pastBias)
                return DateMatch(reference.plusDays(offset.toLong()), listOf(span), relative.ambiguousDirection)
            }
        }
        return null
    }

    private fun singleWordRelative(
        tokens: List<Token>,
        tracker: SpanTracker,
        reference: LocalDate,
        pastBias: Boolean,
    ): DateMatch? {
        for (token in tokens) {
            val relative = Lexicon.RELATIVE_DAYS[token.text] ?: continue
            if (!tracker.claim(token.span)) continue
            val offset = direction(relative, pastBias)
            return DateMatch(
                reference.plusDays(offset.toLong()),
                listOf(token.span),
                relative.ambiguousDirection,
            )
        }
        return null
    }

    private fun direction(relative: Lexicon.RelativeDay, pastBias: Boolean): Int =
        if (relative.ambiguousDirection && pastBias) -relative.offset else relative.offset

    private fun weekday(
        tokens: List<Token>,
        tracker: SpanTracker,
        reference: LocalDate,
        pastBias: Boolean,
    ): DateMatch? {
        for (i in tokens.indices) {
            val token = tokens[i]
            val day = Lexicon.WEEKDAYS[token.text] ?: continue
            val previous = tokens.getOrNull(i - 1)
            val backwards = pastBias || (previous != null && previous.text in LAST_WORDS)
            val forced = previous != null && previous.text in NEXT_WORDS
            val span = if (previous != null && (previous.text in NEXT_WORDS || previous.text in LAST_WORDS)) {
                Span(previous.start, token.end)
            } else {
                token.span
            }
            if (!tracker.claim(span)) continue
            val date = when {
                backwards -> reference.with(TemporalAdjusters.previous(day))
                forced -> reference.with(TemporalAdjusters.next(day))
                // Bare weekday name: today counts, anything else rolls forward.
                reference.dayOfWeek == day -> reference
                else -> reference.with(TemporalAdjusters.next(day))
            }
            return DateMatch(date, listOf(span))
        }
        return null
    }

    private fun relativeInDays(
        tokens: List<Token>,
        tracker: SpanTracker,
        reference: LocalDate,
        pastBias: Boolean,
    ): DateMatch? {
        for (i in tokens.indices) {
            // English: "in 3 days". Roman Urdu: "3 din baad".
            val english = tokens[i].text in IN_WORDS &&
                Lexicon.numberOf(tokens.getOrNull(i + 1)?.text ?: "") != null &&
                (tokens.getOrNull(i + 2)?.text ?: "") in DAY_WORDS
            if (english) {
                val amount = Lexicon.numberOf(tokens[i + 1].text)!!
                val span = Span(tokens[i].start, tokens[i + 2].end)
                if (tracker.claim(span)) {
                    return DateMatch(reference.plusDays(amount.toLong()), listOf(span))
                }
            }
            val roman = Lexicon.numberOf(tokens[i].text) != null &&
                (tokens.getOrNull(i + 1)?.text ?: "") in DAY_WORDS &&
                (tokens.getOrNull(i + 2)?.text ?: "") in IN_WORDS
            if (roman) {
                val amount = Lexicon.numberOf(tokens[i].text)!!
                val span = Span(tokens[i].start, tokens[i + 2].end)
                if (tracker.claim(span)) {
                    val days = if (pastBias) -amount else amount
                    return DateMatch(reference.plusDays(days.toLong()), listOf(span))
                }
            }
        }
        return null
    }

    private fun explicitCalendarDate(
        tokens: List<Token>,
        tracker: SpanTracker,
        reference: LocalDate,
    ): DateMatch? {
        for (i in tokens.indices) {
            val token = tokens[i]
            if (!tracker.isFree(token.span)) continue

            ISO_DATE.matchEntire(token.text)?.let { m ->
                val date = safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                if (date != null && tracker.claim(token.span)) return DateMatch(date, listOf(token.span))
            }

            SLASH_DATE.matchEntire(token.text)?.let { m ->
                val day = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                val yearText = m.groupValues[3]
                val year = when {
                    yearText.isEmpty() -> reference.year
                    yearText.length == 2 -> 2000 + yearText.toInt()
                    else -> yearText.toInt()
                }
                val date = safeDate(year, month, day)
                if (date != null && tracker.claim(token.span)) {
                    // A bare day/month in the past most likely means next year.
                    val rolled = if (yearText.isEmpty() && date.isBefore(reference)) date.plusYears(1) else date
                    return DateMatch(rolled, listOf(token.span))
                }
            }

            // "16 september" / "16 sep 2026"
            val monthAfter = Lexicon.MONTHS[tokens.getOrNull(i + 1)?.text ?: ""]
            val dayValue = token.asInt
            if (dayValue != null && monthAfter != null) {
                val yearToken = tokens.getOrNull(i + 2)?.asInt?.takeIf { it in 1970..2999 }
                val date = safeDate(yearToken ?: reference.year, monthAfter, dayValue)
                if (date != null) {
                    val end = if (yearToken != null) tokens[i + 2].end else tokens[i + 1].end
                    val span = Span(token.start, end)
                    if (tracker.claim(span)) {
                        val rolled = if (yearToken == null && date.isBefore(reference)) date.plusYears(1) else date
                        return DateMatch(rolled, listOf(span))
                    }
                }
            }

            // "september 16"
            val monthHere = Lexicon.MONTHS[token.text]
            val dayAfter = tokens.getOrNull(i + 1)?.asInt
            if (monthHere != null && dayAfter != null && dayAfter in 1..31) {
                val yearToken = tokens.getOrNull(i + 2)?.asInt?.takeIf { it in 1970..2999 }
                val date = safeDate(yearToken ?: reference.year, monthHere, dayAfter)
                if (date != null) {
                    val end = if (yearToken != null) tokens[i + 2].end else tokens[i + 1].end
                    val span = Span(token.start, end)
                    if (tracker.claim(span)) {
                        val rolled = if (yearToken == null && date.isBefore(reference)) date.plusYears(1) else date
                        return DateMatch(rolled, listOf(span))
                    }
                }
            }
        }
        return null
    }

    /** Returns null instead of throwing on "31 february", so bad input becomes a clarifying question. */
    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = runCatching {
        LocalDate.of(year, month, day)
    }.getOrNull()

    /**
     * When the user gives a time but no date, "today" only makes sense while that time is still
     * ahead; a task asked for at "8 am" at 9 pm belongs to tomorrow.
     */
    fun inferDateForTime(time: LocalTime, referenceDate: LocalDate, referenceTime: LocalTime): LocalDate =
        if (time.isAfter(referenceTime)) referenceDate else referenceDate.plusDays(1)

    val WEEKDAYS_MON_TO_FRI: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )
}
