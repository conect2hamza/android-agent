package com.personal.assistant.assistant

import com.personal.assistant.core.ai.AiContext
import com.personal.assistant.core.ai.AssistantCommand
import com.personal.assistant.core.ai.AssistantQuery
import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.ai.ParseResult
import com.personal.assistant.core.ai.QueryKind
import com.personal.assistant.core.ai.TaskDraft
import com.personal.assistant.core.ai.TaskReference
import com.personal.assistant.core.domain.Conflict
import com.personal.assistant.core.domain.ReportGenerator
import com.personal.assistant.core.domain.ResponseComposer
import com.personal.assistant.core.domain.SearchEngine
import com.personal.assistant.core.domain.TimeTracker
import com.personal.assistant.core.model.Sender
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.data.repository.ChatRepository
import com.personal.assistant.data.repository.MemoryRepository
import com.personal.assistant.data.repository.SettingsRepository
import com.personal.assistant.data.repository.TaskRepository
import java.time.LocalDateTime

/** What one exchange produced, for the chat screen to render. */
data class AssistantTurn(
    val reply: String,
    val taskId: Long? = null,
    val conflicts: List<Conflict> = emptyList(),
    val awaitingAnswer: Boolean = false,
    val candidates: List<Task> = emptyList(),
)

/**
 * Executes a parsed command against the database and writes the reply.
 *
 * This class is where the specification's reliability rule is actually enforced. The language layer
 * hands over an [AssistantCommand] and nothing else; every write below goes through the repositories,
 * every number in the reply is read back from what was stored, and anything ambiguous becomes a
 * question rather than a guess. A wrong parse can therefore waste a turn, but it cannot quietly put the
 * wrong thing in the user's calendar.
 */
class AssistantService(
    private val interpreterFactory: suspend () -> com.personal.assistant.core.ai.AssistantInterpreter,
    private val tasks: TaskRepository,
    private val memories: MemoryRepository,
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
    private val composer: ResponseComposer = ResponseComposer(),
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {

    /**
     * The question the assistant is waiting on an answer to.
     *
     * Held in memory rather than the database: it is meaningful for the next message only, and
     * persisting it would resurrect a stale question days later after a process restart.
     */
    private var pending: AssistantCommand.Clarify? = null

    suspend fun send(input: String): AssistantTurn {
        val at = now()
        val conversation = chat.activeConversation(at)
        chat.append(conversation.id, Sender.USER, input, at)

        val turn = runCatching { handle(input, at) }.getOrElse { error ->
            AssistantTurn(
                reply = "Something went wrong handling that: ${error.message ?: "unknown error"}. " +
                    "Nothing was changed.",
            )
        }

        chat.append(
            conversationId = conversation.id,
            sender = Sender.ASSISTANT,
            content = turn.reply,
            at = now(),
            relatedTaskId = turn.taskId,
            awaitingConfirmation = turn.awaitingAnswer,
        )
        return turn
    }

    private suspend fun handle(input: String, at: LocalDateTime): AssistantTurn {
        val preferences = settings.current()
        val context = AiContext(
            now = at,
            categoryNames = tasks.categories().map { it.name },
            memories = if (preferences.memoryEnabled) memories.forPrompt() else emptyList(),
            recentTaskTitles = tasks.recentTitles(),
            firstDayOfWeek = preferences.firstDayOfWeek,
            pendingClarification = pending,
        )

        val answered = pending?.let { question -> completePending(question, input, context) }
        val result: ParseResult = answered ?: interpreterFactory().interpret(input, context)
        pending = null

        return when (val command = result.command) {
            is AssistantCommand.CreateTask -> createTask(command.draft, at, preferences.defaultReminderMinutes, preferences.conflictWarningsEnabled)
            is AssistantCommand.RescheduleTask -> reschedule(command, at)
            is AssistantCommand.ChangeStatus -> changeStatus(command.reference, command.status, at)
            is AssistantCommand.SetProgress -> setProgress(command.reference, command.percent, at)
            is AssistantCommand.DeleteTask -> delete(command.reference, at)
            is AssistantCommand.RememberFact -> remember(command, at, preferences.memoryEnabled)
            is AssistantCommand.Ask -> answer(command.query, at)
            is AssistantCommand.Clarify -> {
                pending = command
                AssistantTurn(reply = command.question, awaitingAnswer = true)
            }
            is AssistantCommand.SmallTalk -> AssistantTurn(reply = command.reply)
            is AssistantCommand.Unsupported -> AssistantTurn(reply = "I didn't catch that. Try again?")
        }
    }

    /**
     * Folds a reply to a question back into the request it came from, so "5 PM" after "When should I
     * schedule it?" completes the original task instead of being parsed as a new one.
     */
    private suspend fun completePending(
        question: AssistantCommand.Clarify,
        input: String,
        context: AiContext,
    ): ParseResult? {
        val draft = question.pendingDraft
        val reference = question.pendingReference
        val parsed = interpreterFactory().interpret(input, context)

        return when {
            draft != null -> {
                val supplement = (parsed.command as? AssistantCommand.CreateTask)?.draft
                val merged = when (question.missing) {
                    com.personal.assistant.core.ai.MissingField.TITLE ->
                        draft.copy(title = supplement?.title?.ifBlank { input.trim() } ?: input.trim())

                    com.personal.assistant.core.ai.MissingField.DATE,
                    com.personal.assistant.core.ai.MissingField.TIME,
                    -> draft.copy(
                        date = supplement?.date ?: draft.date,
                        startTime = supplement?.startTime ?: draft.startTime,
                        endTime = supplement?.endTime ?: draft.endTime,
                        meridiemAssumed = supplement?.meridiemAssumed ?: draft.meridiemAssumed,
                    )

                    else -> draft
                }
                if (merged.date == null && merged.startTime == null) null
                else ParseResult(AssistantCommand.CreateTask(merged), parsed.source, parsed.confidence)
            }

            reference != null && question.missing == com.personal.assistant.core.ai.MissingField.PROGRESS_VALUE -> {
                val percent = (parsed.command as? AssistantCommand.SetProgress)?.percent
                percent?.let {
                    ParseResult(AssistantCommand.SetProgress(reference, it), parsed.source, parsed.confidence)
                }
            }

            reference != null -> {
                val move = parsed.command as? AssistantCommand.RescheduleTask
                move?.let {
                    ParseResult(
                        AssistantCommand.RescheduleTask(reference, it.newDate, it.newTime),
                        parsed.source,
                        parsed.confidence,
                    )
                }
            }

            else -> null
        }
    }

    // ------------------------------------------------------------------- actions

    private suspend fun createTask(
        draft: TaskDraft,
        at: LocalDateTime,
        defaultReminderMinutes: Int,
        warnAboutConflicts: Boolean,
    ): AssistantTurn {
        if (!draft.hasUsableTitle) {
            val question = AssistantCommand.Clarify(
                "What should I call this task?",
                com.personal.assistant.core.ai.MissingField.TITLE,
                pendingDraft = draft,
            )
            pending = question
            return AssistantTurn(reply = question.question, awaitingAnswer = true)
        }

        val outcome = tasks.create(draft, at, defaultReminderMinutes)
        val reply = buildString {
            append(composer.taskCreated(outcome.task, at.toLocalDate(), draft))
            if (outcome.occurrencesCreated > 1) {
                append(" Repeats ")
                append(draft.recurrence?.let { rule -> rule.frequency.name.lowercase() } ?: "regularly")
                append("; ${outcome.occurrencesCreated} dates scheduled so far.")
            }
            if (warnAboutConflicts && outcome.conflicts.isNotEmpty()) {
                appendLine()
                appendLine()
                append(composer.conflictWarning(outcome.conflicts))
            }
        }
        return AssistantTurn(reply, taskId = outcome.task.id, conflicts = outcome.conflicts)
    }

    private suspend fun reschedule(
        command: AssistantCommand.RescheduleTask,
        at: LocalDateTime,
    ): AssistantTurn {
        val candidates = resolve(command.reference, at)
        val target = single(candidates) ?: return ambiguous(candidates, at, "move")
        val moved = tasks.reschedule(target.id, command.newDate, command.newTime, at).getOrNull()
            ?: return AssistantTurn("I couldn't move that task -- it may have been deleted.")
        return AssistantTurn(composer.rescheduled(moved, at.toLocalDate()), taskId = moved.id)
    }

    private suspend fun changeStatus(
        reference: TaskReference,
        status: TaskStatus,
        at: LocalDateTime,
    ): AssistantTurn {
        val candidates = resolve(reference, at)
        val target = single(candidates) ?: return ambiguous(candidates, at, "update")
        return tasks.changeStatus(target.id, status, at).fold(
            onSuccess = { AssistantTurn(composer.statusChanged(it), taskId = it.id) },
            onFailure = { AssistantTurn(it.message ?: "That status change isn't possible.") },
        )
    }

    private suspend fun setProgress(reference: TaskReference, percent: Int, at: LocalDateTime): AssistantTurn {
        val candidates = resolve(reference, at).ifEmpty { activeCandidates(at) }
        val target = single(candidates) ?: return ambiguous(candidates, at, "update")
        return tasks.setProgress(target.id, percent, at).fold(
            onSuccess = { AssistantTurn(composer.progressRecorded(it), taskId = it.id) },
            onFailure = { AssistantTurn("I couldn't record that progress.") },
        )
    }

    private suspend fun delete(reference: TaskReference, at: LocalDateTime): AssistantTurn {
        val candidates = resolve(reference, at)
        val target = single(candidates) ?: return ambiguous(candidates, at, "delete")
        tasks.delete(target.id)
        return AssistantTurn("Deleted \"${target.title}\".")
    }

    private suspend fun remember(
        command: AssistantCommand.RememberFact,
        at: LocalDateTime,
        memoryEnabled: Boolean,
    ): AssistantTurn {
        if (!memoryEnabled) {
            return AssistantTurn("Memory is switched off in settings, so I didn't save that.")
        }
        val memory = memories.remember(command.content, command.category, command.importance, at)
        return AssistantTurn(composer.memorySaved(memory.content))
    }

    private suspend fun answer(query: AssistantQuery, at: LocalDateTime): AssistantTurn {
        val preferences = settings.current()
        val inRange = tasks.range(query.range)
        val generator = ReportGenerator(tasks.categories())
        val engine = SearchEngine(tasks.categories())

        return when (query.kind) {
            QueryKind.PLAN -> AssistantTurn(
                composer.plan(query.range.start, tasks.range(DateRange.single(query.range.start)), at.toLocalDate()),
            )

            QueryKind.COMPLETED -> AssistantTurn(
                composer.taskList("Completed", engine.run(query, inRange, at).map { it.task }, at.toLocalDate()),
            )

            QueryKind.MISSED -> AssistantTurn(
                composer.taskList("Missed", engine.run(query, inRange, at).map { it.task }, at.toLocalDate()),
            )

            QueryKind.UPCOMING -> AssistantTurn(
                composer.taskList("Upcoming", engine.run(query, inRange, at).map { it.task }, at.toLocalDate()),
            )

            QueryKind.SEARCH -> {
                val hits = engine.run(query, inRange, at).map { it.task }
                if (hits.isEmpty()) {
                    AssistantTurn(composer.nothingFound("matching tasks"))
                } else {
                    AssistantTurn(composer.taskList("Found", hits, at.toLocalDate()))
                }
            }

            QueryKind.TIME_SPENT -> {
                val matching = engine.run(query, inRange, at).map { it.task }
                val logs = tasks.logsInRange(query.range)
                val minutes = matching.sumOf { task ->
                    TimeTracker.tracked(logs.filter { it.taskId == task.id }, at).activeMinutes
                }
                val label = query.keywords.joinToString(" ").ifBlank { "that" }
                AssistantTurn(composer.timeSpent(label, minutes))
            }

            QueryKind.DAILY_REPORT -> AssistantTurn(
                composer.dailyReport(
                    generator.daily(
                        query.range.start,
                        tasks.range(DateRange.single(query.range.start)),
                        tasks.logsInRange(DateRange.single(query.range.start)),
                        at,
                    ),
                    at.toLocalDate(),
                ),
            )

            QueryKind.WEEKLY_REPORT -> AssistantTurn(
                composer.weeklyReport(
                    generator.weekly(query.range, inRange, tasks.logsInRange(query.range), at),
                ),
            )

            QueryKind.MONTHLY_REPORT -> AssistantTurn(
                composer.monthlyReport(
                    generator.monthly(
                        query.range,
                        inRange,
                        tasks.logsInRange(query.range),
                        at,
                        preferences.firstDayOfWeek,
                    ),
                ),
            )
        }
    }

    // ------------------------------------------------------------------- helpers

    private suspend fun resolve(reference: TaskReference, at: LocalDateTime): List<Task> {
        reference.taskId?.let { id -> return listOfNotNull(tasks.task(id)) }
        if (reference.isEmpty) return activeCandidates(at)
        return tasks.resolveReference(reference.titleQuery, reference.date, reference.time, at)
    }

    /** Whatever is running right now, for a bare "I've finished about half". */
    private suspend fun activeCandidates(at: LocalDateTime): List<Task> =
        tasks.range(DateRange.single(at.toLocalDate()))
            .filter { it.status.isActive }
            .ifEmpty {
                tasks.range(DateRange.single(at.toLocalDate())).filterNot { it.status.isTerminal }
            }

    /**
     * Acts only on an unambiguous match. Where more than one task fits, the user picks: the
     * specification is explicit that the assistant must not make destructive assumptions.
     */
    private fun single(candidates: List<Task>): Task? = when {
        candidates.isEmpty() -> null
        candidates.size == 1 -> candidates.first()
        else -> null
    }

    private fun ambiguous(candidates: List<Task>, at: LocalDateTime, verb: String): AssistantTurn =
        if (candidates.isEmpty()) {
            AssistantTurn("I couldn't find a task to $verb. Which one did you mean?", awaitingAnswer = true)
        } else {
            AssistantTurn(
                reply = composer.ambiguousReference(candidates, at.toLocalDate()),
                awaitingAnswer = true,
                candidates = candidates.take(5),
            )
        }
}
