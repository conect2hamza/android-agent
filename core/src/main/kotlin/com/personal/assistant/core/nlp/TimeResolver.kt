package com.personal.assistant.core.nlp

import java.time.LocalTime

/**
 * A clock time found in the input.
 *
 * [meridiemAssumed] is the honest half of this type. "4 baje" and "at 4" do not say AM or PM, so the
 * resolver applies a documented working-hours heuristic and flags it. The assistant surfaces the
 * assumption in its confirmation ("scheduled for 4:00 PM -- tap Edit if you meant morning") rather
 * than blocking the user with a question, and never silently pretends the input was precise.
 */
data class TimeMatch(
    val time: LocalTime,
    val spans: List<Span>,
    val meridiemAssumed: Boolean = false,
)

data class TimeRangeMatch(
    val start: TimeMatch,
    val end: TimeMatch,
    val spans: List<Span>,
)

object TimeResolver {

    private val AM_WORDS = setOf("am", "a.m", "a.m.")
    private val PM_WORDS = setOf("pm", "p.m", "p.m.")
    private val AT_WORDS = setOf("at", "@", "by", "around", "from", "se", "سے")
    private val RANGE_WORDS = setOf("to", "till", "until", "untill", "-", "--", "–", "—", "tak", "se", "تک", "سے")

    /**
     * "to" introduces a time in "move my website task to 6", but not in "talk to 3 people". A bare
     * hour after one of these counts as a time only when nothing non-temporal follows it, which is
     * narrow enough to keep the false positives out.
     */
    private val RANGE_INTRODUCERS = setOf("to", "till", "until", "tak", "\u062A\u06A9")

    private val HH_MM = Regex("""^(\d{1,2})[:.](\d{2})$""")
    private val HH_MERIDIEM = Regex("""^(\d{1,2})(am|pm)$""")
    private val HH_MM_MERIDIEM = Regex("""^(\d{1,2})[:.](\d{2})(am|pm)$""")

    /**
     * Finds all clock times in token order. Tokens already claimed by an earlier matcher (a
     * duration, for instance) are skipped, which is what stops "2 hours" from being read as 2 AM.
     */
    fun findAll(tokens: List<Token>, tracker: SpanTracker): List<TimeMatch> {
        val dayPart = findDayPart(tokens)
        val found = mutableListOf<TimeMatch>()

        var i = 0
        while (i < tokens.size) {
            val match = matchAt(tokens, i, dayPart, tracker)
            if (match == null) {
                i++
                continue
            }
            val (timeMatch, consumedTokens) = match
            if (timeMatch.spans.all { tracker.isFree(it) }) {
                tracker.claimAll(timeMatch.spans)
                found += timeMatch
                i += consumedTokens
            } else {
                i++
            }
        }
        return found
    }

    /** Pairs up two times joined by "to" / "till" / "se ... tak" / an en dash. */
    fun asRange(tokens: List<Token>, times: List<TimeMatch>): TimeRangeMatch? {
        if (times.size < 2) return null
        val first = times[0]
        val second = times[1]
        val gapStart = first.spans.maxOf { it.end }
        val gapEnd = second.spans.minOf { it.start }
        if (gapEnd < gapStart) return null
        val between = tokens.filter { it.start >= gapStart && it.end <= gapEnd }
        val joined = between.all { it.text in RANGE_WORDS }
        if (!joined) return null
        if (!second.time.isAfter(first.time)) return null
        return TimeRangeMatch(first, second, first.spans + second.spans)
    }

    fun findDayPart(tokens: List<Token>): Lexicon.DayPart? {
        for (token in tokens) {
            Lexicon.DAY_PARTS[token.text]?.let { return it }
        }
        return null
    }

    /**
     * Extends a time's span backwards over the preposition that introduced it, so "at 10 AM" and
     * "from 4 PM" are removed from the sentence whole. Leaving the bare "at" behind produced titles
     * like "At I have to work on the website".
     */
    private fun withPrefix(tokens: List<Token>, index: Int, end: Int, tracker: SpanTracker): Span {
        val previous = tokens.getOrNull(index - 1)
        val extendable = previous != null &&
            (previous.text in AT_WORDS || previous.text in RANGE_WORDS) &&
            tracker.isFree(previous.span)
        return if (extendable) Span(previous!!.start, end) else Span(tokens[index].start, end)
    }

    /** Returns the match plus how many tokens it consumed, or null if no time starts at [index]. */
    private fun matchAt(
        tokens: List<Token>,
        index: Int,
        dayPart: Lexicon.DayPart?,
        tracker: SpanTracker,
    ): Pair<TimeMatch, Int>? {
        val token = tokens[index]
        if (!tracker.isFree(token.span)) return null

        HH_MM_MERIDIEM.matchEntire(token.text)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val pm = m.groupValues[3] == "pm"
            val time = build(hour, minute, explicitPm = pm) ?: return null
            return TimeMatch(time, listOf(withPrefix(tokens, index, token.end, tracker))) to 1
        }

        HH_MERIDIEM.matchEntire(token.text)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val pm = m.groupValues[2] == "pm"
            val time = build(hour, 0, explicitPm = pm) ?: return null
            return TimeMatch(time, listOf(withPrefix(tokens, index, token.end, tracker))) to 1
        }

        HH_MM.matchEntire(token.text)?.let { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val next = tokens.getOrNull(index + 1)
            val explicitPm = when (next?.text ?: "") {
                in AM_WORDS -> false
                in PM_WORDS -> true
                else -> null
            }
            val consumesNext = explicitPm != null ||
                (next != null && next.text in Lexicon.OCLOCK_WORDS)
            // A 24-hour reading is unambiguous, so no assumption flag is needed for "17:00".
            val twentyFourHour = hour in 13..23
            val time = build(hour, minute, explicitPm, dayPart) ?: return null
            val span = withPrefix(tokens, index, if (consumesNext && next != null) next.end else token.end, tracker)
            val assumed = !twentyFourHour && explicitPm == null && dayPart == null && hour in 1..11
            return TimeMatch(time, listOf(span), meridiemAssumed = assumed) to
                if (consumesNext) 2 else 1
        }

        // A bare hour, either digits ("4") or a number word ("panch").
        val hour = Lexicon.numberOf(token.text) ?: return null
        if (hour !in 0..23) return null

        val next = tokens.getOrNull(index + 1)
        val previous = tokens.getOrNull(index - 1)

        val explicitPm = when (next?.text ?: "") {
            in AM_WORDS -> false
            in PM_WORDS -> true
            else -> null
        }
        val oclock = next != null && next.text in Lexicon.OCLOCK_WORDS
        val introduced = previous != null &&
            (previous.text in AT_WORDS || Lexicon.DAY_PARTS.containsKey(previous.text))
        val introducedByRange = previous != null &&
            previous.text in RANGE_INTRODUCERS &&
            (next == null || Lexicon.DAY_PARTS.containsKey(next.text) || next.text in Lexicon.OCLOCK_WORDS)
        val afterDayWord = previous != null &&
            (Lexicon.RELATIVE_DAYS.containsKey(previous.text) || Lexicon.WEEKDAYS.containsKey(previous.text))

        // Never read a number as a time when it is really quantifying something else.
        val followedByUnit = next != null &&
            (next.text in Lexicon.HOUR_WORDS || next.text in Lexicon.MINUTE_WORDS)
        if (followedByUnit) return null

        val isTime = explicitPm != null || oclock || introduced || introducedByRange || afterDayWord
        if (!isTime) return null

        val time = build(hour, 0, explicitPm, dayPart) ?: return null
        val consumesNext = explicitPm != null || oclock
        val span = withPrefix(tokens, index, if (consumesNext && next != null) next.end else token.end, tracker)
        val assumed = explicitPm == null && dayPart == null && hour in 1..11
        return TimeMatch(time, listOf(span), meridiemAssumed = assumed) to if (consumesNext) 2 else 1
    }

    /**
     * Turns an hour/minute pair into a [LocalTime].
     *
     * Precedence: an explicit AM/PM marker, then a day-part word anywhere in the sentence
     * ("shaam 5 baje" is 5 PM), then the working-hours heuristic: 1-7 reads as afternoon/evening,
     * 8-11 as morning, 12 as noon.
     */
    private fun build(
        hour: Int,
        minute: Int,
        explicitPm: Boolean? = null,
        dayPart: Lexicon.DayPart? = null,
    ): LocalTime? {
        if (minute !in 0..59) return null
        if (hour !in 0..23) return null

        val resolvedHour = when {
            hour == 0 -> 0
            hour > 12 -> hour
            explicitPm == true -> if (hour == 12) 12 else hour + 12
            explicitPm == false -> if (hour == 12) 0 else hour
            dayPart != null -> when {
                !dayPart.pm -> if (hour == 12) 0 else hour
                hour == 12 -> 12
                dayPart == Lexicon.DayPart.NIGHT && hour in 1..3 -> hour // 1 AM is still "raat"
                else -> hour + 12
            }
            hour == 12 -> 12
            hour in 1..7 -> hour + 12
            else -> hour
        }
        if (resolvedHour !in 0..23) return null
        return LocalTime.of(resolvedHour, minute)
    }
}
