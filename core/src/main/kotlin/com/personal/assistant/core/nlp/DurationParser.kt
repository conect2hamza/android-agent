package com.personal.assistant.core.nlp

/** A span of work length, e.g. "two hours", "2 ghante", "45 minat", "half an hour". */
data class DurationMatch(val minutes: Int, val spans: List<Span>)

object DurationParser {

    /** "derh" / "ڈیڑھ" is one-and-a-half; "dhai" / "ڈھائی" is two-and-a-half. */
    private val FRACTIONAL_HOURS: Map<String, Int> = mapOf(
        "derh" to 90, "dedh" to 90, "ڈیڑھ" to 90,
        "dhai" to 150, "ڈھائی" to 150,
    )

    fun find(tokens: List<Token>, tracker: SpanTracker): DurationMatch? {
        for (i in tokens.indices) {
            val token = tokens[i]
            if (!tracker.isFree(token.span)) continue

            // "derh ghanta"
            FRACTIONAL_HOURS[token.text]?.let { minutes ->
                val next = tokens.getOrNull(i + 1)
                if (next != null && next.text in Lexicon.HOUR_WORDS) {
                    val spans = listOf(token.span, next.span)
                    if (spans.all { tracker.isFree(it) }) {
                        tracker.claimAll(spans)
                        return DurationMatch(minutes, spans)
                    }
                }
            }

            // "half an hour" / "adha ghanta"
            if (token.text in Lexicon.HALF_WORDS) {
                val window = (1..2).firstNotNullOfOrNull { offset ->
                    tokens.getOrNull(i + offset)?.takeIf { it.text in Lexicon.HOUR_WORDS }
                }
                if (window != null) {
                    val spans = listOf(Span(token.start, window.end))
                    if (spans.all { tracker.isFree(it) }) {
                        tracker.claimAll(spans)
                        return DurationMatch(30, spans)
                    }
                }
            }

            val amount = Lexicon.numberOf(token.text) ?: continue
            val unit = tokens.getOrNull(i + 1) ?: continue
            val minutes = when {
                unit.text in Lexicon.HOUR_WORDS -> amount * 60
                unit.text in Lexicon.MINUTE_WORDS -> amount
                else -> continue
            }
            if (minutes <= 0 || minutes > 24 * 60) continue

            val spans = mutableListOf(Span(token.start, unit.end))
            var total = minutes

            // "2 hours 30 minutes" / "2 ghante 30 minat"
            val third = tokens.getOrNull(i + 2)
            val fourth = tokens.getOrNull(i + 3)
            if (unit.text in Lexicon.HOUR_WORDS && third != null && fourth != null) {
                val extraAmount = Lexicon.numberOf(third.text.removePrefix("and"))
                    ?: Lexicon.numberOf(third.text)
                if (extraAmount != null && fourth.text in Lexicon.MINUTE_WORDS) {
                    total += extraAmount
                    spans += Span(third.start, fourth.end)
                }
            }

            if (spans.all { tracker.isFree(it) }) {
                tracker.claimAll(spans)
                return DurationMatch(total, spans)
            }
        }
        return null
    }

    /** "remind me 10 minutes before", "10 minat pehle", "1 ghanta pehle". */
    fun findReminderLead(tokens: List<Token>, tracker: SpanTracker): DurationMatch? {
        val beforeWords = setOf("before", "pehle", "pehlay", "prior", "پہلے")
        for (i in tokens.indices) {
            if (tokens[i].text !in beforeWords) continue
            // Look back for "<amount> <unit>" immediately preceding the "before".
            val unit = tokens.getOrNull(i - 1) ?: continue
            val amountToken = tokens.getOrNull(i - 2) ?: continue
            val amount = Lexicon.numberOf(amountToken.text) ?: continue
            val minutes = when {
                unit.text in Lexicon.HOUR_WORDS -> amount * 60
                unit.text in Lexicon.MINUTE_WORDS -> amount
                else -> continue
            }
            val span = Span(amountToken.start, tokens[i].end)
            if (tracker.claim(span)) return DurationMatch(minutes, listOf(span))
        }
        return null
    }
}
