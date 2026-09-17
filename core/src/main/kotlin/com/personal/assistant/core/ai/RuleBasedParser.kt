package com.personal.assistant.core.ai

import com.personal.assistant.core.model.MemoryCategory
import com.personal.assistant.core.model.Priority
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.core.nlp.DateResolver
import com.personal.assistant.core.nlp.DurationParser
import com.personal.assistant.core.nlp.Intent
import com.personal.assistant.core.nlp.IntentClassifier
import com.personal.assistant.core.nlp.Lexicon
import com.personal.assistant.core.nlp.PercentParser
import com.personal.assistant.core.nlp.QueryInterpreter
import com.personal.assistant.core.nlp.RecurrenceParser
import com.personal.assistant.core.nlp.SpanTracker
import com.personal.assistant.core.nlp.TaskTitleBuilder
import com.personal.assistant.core.nlp.TextNormalizer
import com.personal.assistant.core.nlp.TimeResolver
import com.personal.assistant.core.nlp.Token
import com.personal.assistant.core.nlp.Tokenizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Deterministic parser for English, Urdu and Roman Urdu task talk.
 *
 * This is not a fallback bolted on beside the model -- it is the floor the product stands on. The
 * specification requires the app to keep working when the language model is missing, too large for
 * the device, or returns nonsense, and requires that the model never be the source of truth. Both
 * follow from this class being able to answer on its own, and from the model being restricted to
 * proposing the same [AssistantCommand] values this produces.
 *
 * The matchers run in a fixed order and claim character ranges as they go, so later matchers cannot
 * re-read text an earlier one has already explained. That ordering is what keeps "two hours
 * tomorrow" from scheduling a task at 2 AM.
 */
class RuleBasedParser(
    private val categoryNames: List<String> = emptyList(),
    private val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
) {

    fun parse(input: String, now: LocalDateTime): ParseResult {
        val original = input.trim()
        if (original.isEmpty()) {
            return ParseResult(AssistantCommand.Unsupported("Empty input"), ParseSource.RULES, 1f)
        }

        val normalized = TextNormalizer.normalize(original)
        val tokens = Tokenizer.tokenize(normalized)
        val tracker = SpanTracker()

        val words = tokens.map { it.text }.toSet()
        val percent = PercentParser.find(normalized, tokens)
        val hasPercentSignal = PercentParser.hasPercentSignal(normalized, tokens)

        // Direction-ambiguous day words ("kal") need to know whether the sentence looks backwards
        // before the date is resolved, and both signals are plain word checks that do not depend on
        // any matcher having run.
        val pastBias = IntentClassifier.isQuestion(normalized, words) &&
            IntentClassifier.looksPast(words)

        // Order matters; see the class comment.
        val recurrence = RecurrenceParser.find(tokens, tracker)
        val reminderLead = DurationParser.findReminderLead(tokens, tracker)
        val duration = DurationParser.find(tokens, tracker)
        val times = TimeResolver.findAll(tokens, tracker)
        val timeRange = TimeResolver.asRange(tokens, times)
        val date = DateResolver.find(tokens, tracker, now.toLocalDate(), pastBias)

        // The intent is settled only once the matchers have run: a sentence with no recognisable verb
        // is still a scheduling request if it carries a date or a time.
        val hasTemporalInfo = date != null || times.isNotEmpty() || duration != null || recurrence != null
        val intent = IntentClassifier.classify(normalized, tokens, hasPercentSignal, hasTemporalInfo)

        val consumed = tracker.spans
        val leftover = TaskTitleBuilder.build(original, consumed)

        return when (intent) {
            Intent.CREATE -> buildCreate(
                original, normalized, tokens, leftover, date, times, timeRange,
                duration?.minutes, reminderLead?.minutes, recurrence, now,
            )

            Intent.RESCHEDULE -> buildReschedule(leftover, tokens, date, times, timeRange)

            Intent.COMPLETE -> statusCommand(leftover, date, times, TaskStatus.COMPLETED, 0.8f)
            Intent.CANCEL -> statusCommand(leftover, date, times, TaskStatus.CANCELLED, 0.8f)
            Intent.SKIP -> statusCommand(leftover, date, times, TaskStatus.SKIPPED, 0.8f)

            Intent.DELETE -> ParseResult(
                AssistantCommand.DeleteTask(reference(leftover, date, times)),
                ParseSource.RULES,
                0.7f,
            )

            Intent.PROGRESS -> buildProgress(leftover, date, times, percent)

            Intent.REMEMBER -> buildRemember(original, normalized)

            Intent.QUERY -> ParseResult(
                AssistantCommand.Ask(
                    QueryInterpreter.interpret(
                        normalized = normalized,
                        tokens = tokens,
                        explicitDate = date?.date,
                        explicitRangeSpans = consumed,
                        today = now.toLocalDate(),
                        firstDayOfWeek = firstDayOfWeek,
                        pastBias = pastBias,
                    ),
                ),
                ParseSource.RULES,
                0.75f,
            )

            Intent.SMALL_TALK -> ParseResult(
                AssistantCommand.SmallTalk(smallTalkReply(normalized)),
                ParseSource.RULES,
                0.5f,
            )
        }
    }

    // ------------------------------------------------------------------ create

    private fun buildCreate(
        original: String,
        normalized: String,
        tokens: List<Token>,
        leftover: String,
        date: com.personal.assistant.core.nlp.DateMatch?,
        times: List<com.personal.assistant.core.nlp.TimeMatch>,
        timeRange: com.personal.assistant.core.nlp.TimeRangeMatch?,
        durationMinutes: Int?,
        reminderLead: Int?,
        recurrence: com.personal.assistant.core.nlp.RecurrenceDraft?,
        now: LocalDateTime,
    ): ParseResult {
        val start = timeRange?.start ?: times.firstOrNull()
        val end = timeRange?.end

        var dateInferred = false
        val resolvedDate = when {
            date != null -> date.date
            start != null -> {
                dateInferred = true
                DateResolver.inferDateForTime(start.time, now.toLocalDate(), now.toLocalTime())
            }
            recurrence != null -> now.toLocalDate()
            else -> null
        }

        if (leftover.isBlank()) {
            return ParseResult(
                AssistantCommand.Clarify(
                    question = "What should I call this task?",
                    missing = MissingField.TITLE,
                    pendingDraft = TaskDraft(
                        title = "",
                        date = resolvedDate,
                        startTime = start?.time,
                        endTime = end?.time,
                        durationMinutes = durationMinutes,
                        recurrence = recurrence,
                    ),
                ),
                ParseSource.RULES,
                0.6f,
            )
        }

        if (resolvedDate == null) {
            return ParseResult(
                AssistantCommand.Clarify(
                    question = "When should I schedule \"$leftover\"?",
                    missing = MissingField.DATE,
                    pendingDraft = TaskDraft(title = leftover, durationMinutes = durationMinutes),
                ),
                ParseSource.RULES,
                0.6f,
            )
        }

        val draft = TaskDraft(
            title = leftover,
            date = resolvedDate,
            startTime = start?.time,
            endTime = end?.time,
            durationMinutes = durationMinutes ?: impliedDuration(start?.time, end?.time),
            priority = detectPriority(tokens),
            categoryName = guessCategory(leftover),
            reminderMinutes = when {
                reminderLead != null -> reminderLead
                start != null -> Task.DEFAULT_REMINDER_MINUTES
                else -> null
            },
            recurrence = recurrence,
            meridiemAssumed = start?.meridiemAssumed == true,
            dateDirectionAssumed = date?.ambiguousDirection == true,
            dateInferred = dateInferred,
        )

        // Confidence drops for every guess the parser had to make, which is what drives the UI to
        // show a confirmation card rather than acting silently.
        var confidence = 0.9f
        if (draft.meridiemAssumed) confidence -= 0.2f
        if (draft.dateDirectionAssumed) confidence -= 0.1f
        if (draft.dateInferred) confidence -= 0.05f
        if (start == null) confidence -= 0.1f

        return ParseResult(AssistantCommand.CreateTask(draft), ParseSource.RULES, confidence.coerceIn(0f, 1f))
    }

    private fun impliedDuration(
        start: java.time.LocalTime?,
        end: java.time.LocalTime?,
    ): Int? {
        if (start == null || end == null) return null
        val minutes = java.time.Duration.between(start, end).toMinutes().toInt()
        return minutes.takeIf { it > 0 }
    }

    // -------------------------------------------------------------- reschedule

    private fun buildReschedule(
        leftover: String,
        tokens: List<Token>,
        date: com.personal.assistant.core.nlp.DateMatch?,
        times: List<com.personal.assistant.core.nlp.TimeMatch>,
        timeRange: com.personal.assistant.core.nlp.TimeRangeMatch?,
    ): ParseResult {
        // "Move my 4 PM task to 6 PM": the first time identifies the task, the second is the target.
        val referenceTime = if (times.size >= 2) times.first().time else null
        val newTime = timeRange?.start?.time ?: times.lastOrNull()?.time

        val target = TaskReference(
            titleQuery = cleanReference(leftover).takeIf { it.isNotBlank() },
            date = null,
            time = referenceTime,
        )

        if (target.isEmpty && newTime == null && date == null) {
            return ParseResult(
                AssistantCommand.Clarify(
                    question = "Which task should I move, and to when?",
                    missing = MissingField.TASK_IDENTITY,
                ),
                ParseSource.RULES,
                0.5f,
            )
        }

        if (date == null && newTime == null) {
            return ParseResult(
                AssistantCommand.Clarify(
                    question = "When should I move it to?",
                    missing = MissingField.TIME,
                    pendingReference = target,
                ),
                ParseSource.RULES,
                0.6f,
            )
        }

        return ParseResult(
            AssistantCommand.RescheduleTask(target, newDate = date?.date, newTime = newTime),
            ParseSource.RULES,
            if (target.isEmpty) 0.6f else 0.85f,
        )
    }

    // ------------------------------------------------------------------ status

    private fun statusCommand(
        leftover: String,
        date: com.personal.assistant.core.nlp.DateMatch?,
        times: List<com.personal.assistant.core.nlp.TimeMatch>,
        status: TaskStatus,
        confidence: Float,
    ): ParseResult = ParseResult(
        AssistantCommand.ChangeStatus(reference(leftover, date, times), status),
        ParseSource.RULES,
        confidence,
    )

    private fun buildProgress(
        leftover: String,
        date: com.personal.assistant.core.nlp.DateMatch?,
        times: List<com.personal.assistant.core.nlp.TimeMatch>,
        percent: Int?,
    ): ParseResult {
        if (percent == null) {
            return ParseResult(
                AssistantCommand.Clarify(
                    question = "How far along are you? You can say a percentage, \"half\", or \"done\".",
                    missing = MissingField.PROGRESS_VALUE,
                    pendingReference = reference(leftover, date, times),
                ),
                ParseSource.RULES,
                0.6f,
            )
        }
        return ParseResult(
            AssistantCommand.SetProgress(reference(leftover, date, times), percent),
            ParseSource.RULES,
            0.85f,
        )
    }

    // ------------------------------------------------------------------ memory

    private fun buildRemember(original: String, normalized: String): ParseResult {
        var content = original
        val lowered = normalized
        // Strip the trigger phrase but keep the fact itself verbatim.
        for (phrase in (Lexicon.REMEMBER_WORDS + setOf("that", "ke", "kay")).sortedByDescending { it.length }) {
            val index = lowered.indexOf(phrase)
            if (index == 0) {
                content = original.drop(phrase.length).trimStart(' ', ',', ':')
                break
            }
        }
        content = content.trim().trim('"', '\'')
        if (content.length < 3) {
            return ParseResult(
                AssistantCommand.Clarify(
                    question = "What would you like me to remember?",
                    missing = MissingField.TITLE,
                ),
                ParseSource.RULES,
                0.6f,
            )
        }
        return ParseResult(
            AssistantCommand.RememberFact(
                content = content.replaceFirstChar { it.titlecaseChar() },
                category = guessMemoryCategory(normalized),
                importance = if (Lexicon.HABIT_WORDS.any { normalized.contains(it) }) 4 else 3,
            ),
            ParseSource.RULES,
            0.8f,
        )
    }

    private fun guessMemoryCategory(normalized: String): MemoryCategory = when {
        Lexicon.HABIT_WORDS.any { normalized.contains(it) } -> MemoryCategory.ROUTINE
        listOf("project", "client", "wordpress", "website", "پروجیکٹ").any { normalized.contains(it) } ->
            MemoryCategory.PROJECT
        listOf("goal", "target", "maqsad", "مقصد").any { normalized.contains(it) } -> MemoryCategory.GOAL
        listOf("prefer", "pasand", "پسند").any { normalized.contains(it) } -> MemoryCategory.PREFERENCE
        listOf("every", "daily", "roz", "routine").any { normalized.contains(it) } -> MemoryCategory.ROUTINE
        else -> MemoryCategory.NOTE
    }

    // ----------------------------------------------------------------- helpers

    private fun reference(
        leftover: String,
        date: com.personal.assistant.core.nlp.DateMatch?,
        times: List<com.personal.assistant.core.nlp.TimeMatch>,
    ) = TaskReference(
        titleQuery = cleanReference(leftover).takeIf { it.isNotBlank() },
        date = date?.date,
        time = times.firstOrNull()?.time,
    )

    private val REFERENCE_NOISE = setOf(
        "my", "the", "a", "an", "task", "tasks", "to", "it", "that", "this", "please",
        "mera", "meri", "mere", "wala", "wali", "ka", "ki", "ke", "ko", "kaam",
        "میرا", "میری", "والا", "کا", "کی", "کے", "کو",
    ) + Lexicon.RESCHEDULE_WORDS + Lexicon.COMPLETE_WORDS + Lexicon.CANCEL_WORDS +
        Lexicon.DELETE_WORDS + Lexicon.SKIP_WORDS + Lexicon.PROGRESS_WORDS

    /** Strips the verbs and possessives around a task name so it can be matched against titles. */
    private fun cleanReference(text: String): String = text
        .split(Regex("""\s+"""))
        .map { it.trim(',', '.', '"', '\'', '۔') }
        .filter { it.isNotBlank() && it.lowercase() !in REFERENCE_NOISE }
        .joinToString(" ")
        .trim()

    private val URGENT_WORDS = setOf("urgent", "asap", "immediately", "foran", "فوراً", "ضروری")
    private val HIGH_WORDS = setOf("important", "high", "priority", "zaroori", "اہم")
    private val LOW_WORDS = setOf("whenever", "someday", "low", "optional", "بعد میں")

    private fun detectPriority(tokens: List<Token>): Priority {
        val words = tokens.map { it.text }.toSet()
        return when {
            words.any { it in URGENT_WORDS } -> Priority.URGENT
            words.any { it in HIGH_WORDS } -> Priority.HIGH
            words.any { it in LOW_WORDS } -> Priority.LOW
            else -> Priority.NORMAL
        }
    }

    /**
     * Matches a task title against known category names, plus a small set of built-in hints. Returns
     * null rather than guessing wildly -- an unfiled task is better than a wrongly filed one.
     */
    private fun guessCategory(title: String): String? {
        val lowered = title.lowercase()
        // Specific hints are checked first on purpose. Matching category names directly would file
        // "website work" under "Work" simply because the word appears in it.
        val hints = mapOf(
            "Development" to listOf("website", "wordpress", "code", "coding", "api", "bug", "deploy", "app"),
            "Design" to listOf("design", "figma", "logo", "mockup", "ui", "ux"),
            "Study" to listOf("study", "read", "exam", "course", "assignment", "parhna", "پڑھنا"),
            "Work" to listOf("client", "meeting", "invoice", "report", "email"),
            "Health" to listOf("gym", "walk", "workout", "doctor", "medicine", "sleep"),
        )
        for ((category, keywords) in hints) {
            if (keywords.any { lowered.contains(it) }) {
                return categoryNames.firstOrNull { it.equals(category, ignoreCase = true) } ?: category
            }
        }
        return categoryNames.firstOrNull { lowered.contains(it.lowercase()) }
    }

    private fun smallTalkReply(normalized: String): String = when {
        listOf("hello", "hi ", "hey", "salam", "assalam", "سلام").any { normalized.startsWith(it.trim()) } ->
            "Hello. Tell me what you're planning and I'll schedule it."
        listOf("thanks", "thank you", "shukriya", "شکریہ").any { normalized.contains(it) } ->
            "Anytime."
        else ->
            "I can create tasks, set reminders, track progress and show reports. " +
                "Try \"tomorrow at 4 PM website work\"."
    }
}
