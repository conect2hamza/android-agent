package com.personal.assistant.core.nlp

import com.personal.assistant.core.ai.AssistantQuery
import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.ai.QueryKind
import java.time.DayOfWeek
import java.time.LocalDate

/** Turns a question into a [AssistantQuery] that the search and report engines can execute. */
object QueryInterpreter {

    private val STOPWORDS = setOf(
        "what", "when", "which", "show", "list", "me", "my", "i", "do", "did", "have", "has",
        "the", "a", "an", "of", "on", "for", "in", "at", "to", "is", "are", "was", "were",
        "how", "much", "many", "time", "tasks", "task", "all", "and", "please", "about",
        "kya", "kab", "kitna", "kitni", "mera", "meri", "mujhe", "ka", "ki", "ke", "ko", "hai",
        "hain", "dikhao", "batao", "kaam", "کیا", "کب", "میرا", "میری", "مجھے", "کا", "کی", "کے",
        "ہے", "ہیں", "دکھاؤ", "بتاؤ",
    )

    private val PLAN_WORDS = setOf("plan", "planned", "schedule", "agenda", "routine", "منصوبہ")
    private val MISSED_WORDS = setOf("missed", "miss", "reh", "chhut", "چھوٹ", "رہ")
    private val COMPLETED_WORDS = setOf("completed", "complete", "finished", "done", "kiya", "مکمل")
    private val UPCOMING_WORDS = setOf("upcoming", "next", "coming", "aane", "آنے", "اگلے")
    private val SPENT_WORDS = setOf("spend", "spent", "took", "logged", "tracked", "der", "دیر")
    private val REPORT_WORDS = setOf("report", "summary", "rapoort", "رپورٹ", "خلاصہ")
    private val WEEK_WORDS = setOf("week", "weekly", "hafte", "hafta", "haftawar", "ہفتے", "ہفتہ وار")
    private val MONTH_WORDS = setOf("month", "monthly", "mahine", "maheena", "mahana", "مہینے", "ماہ", "ماہانہ")

    fun interpret(
        normalized: String,
        tokens: List<Token>,
        explicitDate: LocalDate?,
        explicitRangeSpans: List<Span>,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
        pastBias: Boolean,
    ): AssistantQuery {
        val words = tokens.map { it.text }
        val wordSet = words.toSet()

        val mentionsWeek = wordSet.any { it in WEEK_WORDS }
        val mentionsMonth = wordSet.any { it in MONTH_WORDS }
        val isReport = wordSet.any { it in REPORT_WORDS }

        val kind = when {
            isReport && mentionsMonth -> QueryKind.MONTHLY_REPORT
            isReport && mentionsWeek -> QueryKind.WEEKLY_REPORT
            isReport -> QueryKind.DAILY_REPORT
            wordSet.any { it in SPENT_WORDS } -> QueryKind.TIME_SPENT
            wordSet.any { it in MISSED_WORDS } -> QueryKind.MISSED
            wordSet.any { it in UPCOMING_WORDS } -> QueryKind.UPCOMING
            wordSet.any { it in COMPLETED_WORDS } -> QueryKind.COMPLETED
            wordSet.any { it in PLAN_WORDS } -> QueryKind.PLAN
            explicitDate != null -> QueryKind.PLAN
            else -> QueryKind.SEARCH
        }

        val anchor = explicitDate ?: today
        val range = when {
            mentionsMonth -> DateRange.month(anchor)
            mentionsWeek -> DateRange.week(anchor, firstDayOfWeek)
            kind == QueryKind.UPCOMING -> DateRange(today, today.plusDays(7))
            explicitDate != null -> DateRange.single(explicitDate)
            kind == QueryKind.SEARCH -> DateRange(today.minusMonths(6), today.plusMonths(6))
            pastBias -> DateRange.single(today)
            else -> DateRange.single(today)
        }

        val keywords = tokens
            .filterNot { token -> explicitRangeSpans.any { it.overlaps(token.span) } }
            .map { it.text }
            .filter { it.length > 2 && it !in STOPWORDS && Lexicon.numberOf(it) == null }
            .filterNot { it in PLAN_WORDS || it in MISSED_WORDS || it in COMPLETED_WORDS }
            .filterNot { it in UPCOMING_WORDS || it in SPENT_WORDS || it in REPORT_WORDS }
            .filterNot { it in WEEK_WORDS || it in MONTH_WORDS }
            .filterNot { Lexicon.RELATIVE_DAYS.containsKey(it) || Lexicon.WEEKDAYS.containsKey(it) }
            .distinct()

        return AssistantQuery(kind, range, keywords)
    }
}
