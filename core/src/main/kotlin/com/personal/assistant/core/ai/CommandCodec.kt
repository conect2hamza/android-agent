package com.personal.assistant.core.ai

import com.personal.assistant.core.model.MemoryCategory
import com.personal.assistant.core.model.Priority
import com.personal.assistant.core.model.RecurrenceFrequency
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.core.nlp.DateResolver
import com.personal.assistant.core.nlp.RecurrenceDraft
import com.personal.assistant.core.nlp.SpanTracker
import com.personal.assistant.core.nlp.TextNormalizer
import com.personal.assistant.core.nlp.TimeResolver
import com.personal.assistant.core.nlp.Tokenizer
import com.personal.assistant.core.util.JsonValue
import com.personal.assistant.core.util.MiniJson
import com.personal.assistant.core.util.array
import com.personal.assistant.core.util.int
import com.personal.assistant.core.util.obj
import com.personal.assistant.core.util.string
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Turns a language model's raw text into a validated [AssistantCommand], or nothing.
 *
 * This is the trust boundary the specification's reliability rule depends on. Everything arriving here
 * is treated as a suggestion from an untrusted source:
 *
 *  - only the fixed set of intents is accepted, anything else is rejected outright;
 *  - dates and times are re-derived from the user's own wording by the deterministic resolvers, so a
 *    model that hallucinates "2027-13-45" cannot put it in the database;
 *  - numbers are clamped to their legal ranges and text is length-limited;
 *  - a failure at any step returns null, which sends the caller to the rule-based parser.
 */
object CommandCodec {

    private const val MAX_TEXT = 500

    fun decode(raw: String, context: AiContext): AssistantCommand? {
        val json = MiniJson.extractFirstObject(raw) ?: return null
        return decode(json, context)
    }

    fun decode(json: JsonValue.Obj, context: AiContext): AssistantCommand? {
        val intent = json.string("intent")?.trim()?.lowercase() ?: return null
        return when (intent) {
            "create_task", "create", "add_task" -> createTask(json, context)
            "reschedule", "reschedule_task", "move_task" -> reschedule(json, context)
            "change_status", "update_status", "complete_task" -> changeStatus(json, context)
            "set_progress", "progress" -> setProgress(json, context)
            "delete_task", "delete" -> AssistantCommand.DeleteTask(reference(json, context))
            "remember", "remember_fact", "save_memory" -> remember(json)
            "query", "ask", "search" -> query(json, context)
            "clarify", "question" -> json.string("question")?.let {
                AssistantCommand.Clarify(clean(it), MissingField.TITLE)
            }
            "small_talk", "chat" -> AssistantCommand.SmallTalk(
                clean(json.string("reply") ?: "I can schedule tasks, track progress and show reports."),
            )
            // An unrecognised intent is a failed parse, not a reason to improvise.
            else -> null
        }
    }

    // ------------------------------------------------------------------ decoders

    private fun createTask(json: JsonValue.Obj, context: AiContext): AssistantCommand? {
        val title = json.string("title")?.let(::clean)?.takeIf { it.length >= 2 }
        val date = resolveDate(json.string("date_phrase") ?: json.string("date"), context)
        val startTime = resolveTime(json.string("time_phrase") ?: json.string("time"), context)
        val endTime = resolveTime(json.string("end_time_phrase") ?: json.string("end_time"), context)
        val recurrence = recurrence(json.obj("recurrence"), date?.first ?: context.now.toLocalDate())

        if (title == null) {
            return AssistantCommand.Clarify(
                question = json.string("question")?.let(::clean) ?: "What should I call this task?",
                missing = MissingField.TITLE,
            )
        }

        val resolvedDate = date?.first
            ?: startTime?.let {
                DateResolver.inferDateForTime(it.first, context.now.toLocalDate(), context.now.toLocalTime())
            }
            ?: recurrence?.let { context.now.toLocalDate() }

        if (resolvedDate == null) {
            return AssistantCommand.Clarify(
                question = "When should I schedule \"$title\"?",
                missing = MissingField.DATE,
                pendingDraft = TaskDraft(title = title, durationMinutes = json.int("duration_minutes")),
            )
        }

        val draft = TaskDraft(
            title = title,
            description = json.string("description")?.let(::clean),
            date = resolvedDate,
            startTime = startTime?.first,
            endTime = endTime?.first,
            durationMinutes = json.int("duration_minutes")?.takeIf { it in 1..(24 * 60) }
                ?: impliedDuration(startTime?.first, endTime?.first),
            priority = Priority.fromNameOrNull(json.string("priority")) ?: Priority.NORMAL,
            categoryName = json.string("category")?.let { proposed ->
                // Only accept a category that actually exists; never create one from model output.
                context.categoryNames.firstOrNull { it.equals(proposed.trim(), ignoreCase = true) }
            },
            reminderMinutes = json.int("reminder_minutes")?.takeIf { it in 0..(24 * 60) }
                ?: startTime?.let { Task.DEFAULT_REMINDER_MINUTES },
            recurrence = recurrence,
            notes = json.string("notes")?.let(::clean),
            meridiemAssumed = startTime?.second == true,
            dateDirectionAssumed = date?.second == true,
            dateInferred = date == null && startTime != null,
        )
        return AssistantCommand.CreateTask(draft)
    }

    private fun reschedule(json: JsonValue.Obj, context: AiContext): AssistantCommand {
        val date = resolveDate(json.string("date_phrase") ?: json.string("date"), context)
        val time = resolveTime(json.string("time_phrase") ?: json.string("time"), context)
        val target = reference(json, context)
        if (date == null && time == null) {
            return AssistantCommand.Clarify(
                question = "When should I move it to?",
                missing = MissingField.TIME,
                pendingReference = target,
            )
        }
        return AssistantCommand.RescheduleTask(target, newDate = date?.first, newTime = time?.first)
    }

    private fun changeStatus(json: JsonValue.Obj, context: AiContext): AssistantCommand? {
        val status = TaskStatus.fromNameOrNull(json.string("status")) ?: TaskStatus.COMPLETED
        // Only the outcomes a user can actually report are accepted from the model.
        val permitted = setOf(
            TaskStatus.COMPLETED, TaskStatus.PARTIALLY_COMPLETED, TaskStatus.CANCELLED,
            TaskStatus.SKIPPED, TaskStatus.POSTPONED, TaskStatus.MISSED, TaskStatus.STARTED,
        )
        if (status !in permitted) return null
        return AssistantCommand.ChangeStatus(reference(json, context), status)
    }

    private fun setProgress(json: JsonValue.Obj, context: AiContext): AssistantCommand {
        val progress = json.int("progress")
            ?: return AssistantCommand.Clarify(
                question = "How far along are you?",
                missing = MissingField.PROGRESS_VALUE,
                pendingReference = reference(json, context),
            )
        return AssistantCommand.SetProgress(reference(json, context), progress.coerceIn(0, 100))
    }

    private fun remember(json: JsonValue.Obj): AssistantCommand? {
        val content = json.string("memory")?.let(::clean)?.takeIf { it.length >= 3 } ?: return null
        return AssistantCommand.RememberFact(
            content = content,
            category = MemoryCategory.fromNameOrNull(json.string("memory_category")) ?: MemoryCategory.NOTE,
            importance = json.int("importance")?.coerceIn(1, 5) ?: 3,
        )
    }

    private fun query(json: JsonValue.Obj, context: AiContext): AssistantCommand {
        val kind = json.string("query_kind")?.trim()?.uppercase()?.let { name ->
            QueryKind.entries.firstOrNull { it.name == name }
        } ?: QueryKind.SEARCH
        val date = resolveDate(json.string("date_phrase") ?: json.string("date"), context)?.first
        val today = context.now.toLocalDate()
        val range = when (kind) {
            QueryKind.WEEKLY_REPORT -> DateRange.week(date ?: today, context.firstDayOfWeek)
            QueryKind.MONTHLY_REPORT -> DateRange.month(date ?: today)
            QueryKind.UPCOMING -> DateRange(today, today.plusDays(7))
            QueryKind.SEARCH -> date?.let { DateRange.single(it) }
                ?: DateRange(today.minusMonths(6), today.plusMonths(6))
            else -> DateRange.single(date ?: today)
        }
        val keywords = json.array("keywords")
            .mapNotNull { (it as? JsonValue.Str)?.value?.trim() }
            .filter { it.length > 1 }
            .ifEmpty { listOfNotNull(json.string("target")?.let(::clean)) }
        return AssistantCommand.Ask(AssistantQuery(kind, range, keywords))
    }

    private fun reference(json: JsonValue.Obj, context: AiContext): TaskReference = TaskReference(
        titleQuery = (json.string("target") ?: json.string("title"))?.let(::clean),
        date = resolveDate(json.string("date_phrase") ?: json.string("date"), context)?.first,
        time = resolveTime(json.string("target_time_phrase"), context)?.first,
    )

    private fun recurrence(json: JsonValue.Obj?, anchor: LocalDate): RecurrenceDraft? {
        if (json == null) return null
        val frequency = json.string("frequency")?.trim()?.uppercase()?.let { name ->
            RecurrenceFrequency.entries.firstOrNull { it.name == name }
        } ?: return null
        val days = json.array("days_of_week")
            .mapNotNull { (it as? JsonValue.Str)?.value?.trim()?.uppercase() }
            .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
            .toSet()
        return RecurrenceDraft(
            frequency = frequency,
            interval = json.int("interval")?.coerceIn(1, 52) ?: 1,
            daysOfWeek = if (frequency == RecurrenceFrequency.WEEKLY) days else emptySet(),
            dayOfMonth = json.int("day_of_month")?.takeIf { it in 1..31 }
                ?: anchor.dayOfMonth.takeIf { frequency == RecurrenceFrequency.MONTHLY },
            spans = emptyList(),
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Re-resolves the user's own date wording with the deterministic resolver. An ISO date the model
     * echoed back is accepted as-is; anything else ("tomorrow", "kal", "next monday") is recomputed.
     * Returns the date plus whether its direction was ambiguous.
     */
    private fun resolveDate(phrase: String?, context: AiContext): Pair<LocalDate, Boolean>? {
        val text = phrase?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = TextNormalizer.normalize(text)
        val tokens = Tokenizer.tokenize(normalized)
        val match = DateResolver.find(tokens, SpanTracker(), context.now.toLocalDate(), pastBias = false)
        return match?.let { it.date to it.ambiguousDirection }
    }

    /** Same treatment for times, so "4 baje" gets the app's own meridiem rules, not the model's. */
    private fun resolveTime(phrase: String?, context: AiContext): Pair<LocalTime, Boolean>? {
        val text = phrase?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = TextNormalizer.normalize(text)
        val tokens = Tokenizer.tokenize(normalized)
        val matches = TimeResolver.findAll(tokens, SpanTracker())
        // A bare "5" is a time in this field even though it would be ambiguous mid-sentence.
        if (matches.isEmpty()) {
            val bare = normalized.trim().toIntOrNull() ?: return null
            if (bare !in 0..23) return null
            val hour = if (bare in 1..7) bare + 12 else bare
            return LocalTime.of(hour, 0) to true
        }
        val first = matches.first()
        return first.time to first.meridiemAssumed
    }

    private fun impliedDuration(start: LocalTime?, end: LocalTime?): Int? {
        if (start == null || end == null) return null
        return java.time.Duration.between(start, end).toMinutes().toInt().takeIf { it > 0 }
    }

    /** Strips control characters and caps length, so model output cannot smuggle anything in. */
    private fun clean(text: String): String = text
        .filter { it == '\n' || it == '\t' || it.code >= 0x20 }
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(MAX_TEXT)
}
