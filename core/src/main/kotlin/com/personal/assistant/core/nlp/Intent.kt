package com.personal.assistant.core.nlp

enum class Intent {
    CREATE,
    RESCHEDULE,
    COMPLETE,
    PROGRESS,
    CANCEL,
    SKIP,
    DELETE,
    REMEMBER,
    QUERY,
    SMALL_TALK,
}

object IntentClassifier {

    private val PAST_MARKERS = setOf(
        "did", "was", "were", "yesterday", "last", "completed", "finished", "spent",
        "kiya", "ki", "kia", "tha", "thi", "pichle", "guzray", "کیا", "تھا", "تھی", "گزشتہ",
    )

    private val PARTIAL_MARKERS = setOf(
        "half", "halfway", "about", "roughly", "around", "partly", "partially", "some",
        "adha", "aadha", "thora", "kuch", "آدھا", "تھوڑا", "کچھ",
    )

    private val FUTURE_TASK_VERBS = setOf(
        "need", "have", "must", "will", "schedule", "plan", "work", "study", "call", "meet",
        "finish", "start", "karna", "karni", "krna", "karo", "dena", "kaam",
        "کرنا", "کرنی", "کام",
    )

    /**
     * Single-pass classifier. The order of the checks *is* the precedence rule, and it is the part
     * most likely to need tuning against real input, so it is written as an explicit sequence rather
     * than a score.
     *
     * @param hasTemporalInfo true when a date, time, duration or repeat rule was found in the
     *   sentence. It is the fallback signal for CREATE: plenty of real input carries no verb at all
     *   ("Kal shaam 7 baje meeting", "Urgent: invoice tomorrow at 9 AM"), and a sentence that pins
     *   something to the calendar is a scheduling request whether or not it contains a known verb.
     */
    fun classify(
        normalized: String,
        tokens: List<Token>,
        hasPercent: Boolean,
        hasTemporalInfo: Boolean,
    ): Intent {
        val words = tokens.map { it.text }.toSet()
        fun has(vocabulary: Set<String>): Boolean =
            words.any { it in vocabulary } || vocabulary.any { it.contains(' ') && normalized.contains(it) }

        // "Remember that ..." is explicit and outranks everything: it must never become a task.
        if (has(Lexicon.REMEMBER_WORDS) || has(Lexicon.HABIT_WORDS)) return Intent.REMEMBER

        // A question about stored data must not be mistaken for a request to store more.
        if (isQuestion(normalized, words)) return Intent.QUERY

        if (has(Lexicon.RESCHEDULE_WORDS)) return Intent.RESCHEDULE
        if (has(Lexicon.DELETE_WORDS)) return Intent.DELETE
        if (has(Lexicon.CANCEL_WORDS)) return Intent.CANCEL
        if (has(Lexicon.SKIP_WORDS)) return Intent.SKIP

        // "I've finished about half" is progress, not completion -- the partial marker decides.
        val partial = hasPercent || words.any { it in PARTIAL_MARKERS }
        if (partial && (has(Lexicon.PROGRESS_WORDS) || has(Lexicon.COMPLETE_WORDS) || hasPercent)) {
            return Intent.PROGRESS
        }
        if (has(Lexicon.COMPLETE_WORDS)) return Intent.COMPLETE
        if (has(Lexicon.PROGRESS_WORDS)) return Intent.PROGRESS

        if (has(Lexicon.REMIND_WORDS)) return Intent.CREATE
        if (words.any { it in FUTURE_TASK_VERBS }) return Intent.CREATE
        if (hasTemporalInfo) return Intent.CREATE

        return Intent.SMALL_TALK
    }

    fun isQuestion(normalized: String, words: Set<String>): Boolean {
        val trimmed = normalized.trim()
        if (trimmed.endsWith("?") || trimmed.endsWith("؟")) return true
        val opener = words.firstOrNull() ?: return false
        return Lexicon.QUESTION_WORDS.any { question ->
            if (question.contains(' ')) normalized.contains(question) else opener == question || words.contains(question)
        }
    }

    fun looksPast(words: Set<String>): Boolean = words.any { it in PAST_MARKERS }
}
