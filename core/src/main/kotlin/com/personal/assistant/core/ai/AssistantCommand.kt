package com.personal.assistant.core.ai

import com.personal.assistant.core.model.MemoryCategory
import com.personal.assistant.core.model.TaskStatus
import java.time.LocalDate
import java.time.LocalTime

/** Which task the user meant, when they referred to one by words rather than by id. */
data class TaskReference(
    val titleQuery: String? = null,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val taskId: Long? = null,
) {
    val isEmpty: Boolean get() = titleQuery.isNullOrBlank() && date == null && time == null && taskId == null
}

/** Fields the assistant can be missing before it is allowed to act. */
enum class MissingField { TITLE, TIME, DATE, TASK_IDENTITY, PROGRESS_VALUE }

/**
 * The only vocabulary the language layer may speak to the rest of the app.
 *
 * Note what is absent: there is no "run this SQL", no "cancel that alarm", no free-form action. A
 * model -- local or otherwise -- can only ever propose one of these cases, and each one is validated
 * before execution. That is the mechanism behind the rule that the model is never the source of
 * truth.
 */
sealed interface AssistantCommand {

    data class CreateTask(val draft: TaskDraft) : AssistantCommand

    data class RescheduleTask(
        val reference: TaskReference,
        val newDate: LocalDate? = null,
        val newTime: LocalTime? = null,
        val keepDuration: Boolean = true,
    ) : AssistantCommand

    data class ChangeStatus(val reference: TaskReference, val status: TaskStatus) : AssistantCommand

    data class SetProgress(val reference: TaskReference, val percent: Int) : AssistantCommand

    data class DeleteTask(val reference: TaskReference) : AssistantCommand

    data class RememberFact(
        val content: String,
        val category: MemoryCategory = MemoryCategory.NOTE,
        val importance: Int = 3,
    ) : AssistantCommand

    data class Ask(val query: AssistantQuery) : AssistantCommand

    /**
     * The request was understood but is incomplete. The app answers with [question] and keeps the
     * partially filled [pendingDraft] so the follow-up reply completes it instead of starting over.
     */
    data class Clarify(
        val question: String,
        val missing: MissingField,
        val pendingDraft: TaskDraft? = null,
        val pendingReference: TaskReference? = null,
    ) : AssistantCommand

    data class SmallTalk(val reply: String) : AssistantCommand

    data class Unsupported(val reason: String) : AssistantCommand
}

enum class ParseSource {
    /** Deterministic rules only. Always available, including when no model is installed. */
    RULES,

    /** The local language model produced the structured command. */
    MODEL,

    /** The model produced the command and rules filled or corrected part of it. */
    HYBRID,
}

data class ParseResult(
    val command: AssistantCommand,
    val source: ParseSource,
    /** 0f..1f. Low confidence pushes the UI towards asking for confirmation. */
    val confidence: Float,
) {
    init {
        require(confidence in 0f..1f) { "confidence out of range: $confidence" }
    }
}
