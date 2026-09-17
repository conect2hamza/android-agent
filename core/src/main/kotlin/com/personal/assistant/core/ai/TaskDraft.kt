package com.personal.assistant.core.ai

import com.personal.assistant.core.model.Priority
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.nlp.RecurrenceDraft
import java.time.LocalDate
import java.time.LocalTime

/**
 * A *proposed* task. Nothing here is trusted: the draft crosses from the language layer into the
 * application layer, where it is validated and only then turned into a [com.personal.assistant.core.model.Task].
 *
 * The `...Assumed` flags travel with the draft so the confirmation message can admit what was
 * guessed instead of presenting a guess as fact.
 */
data class TaskDraft(
    val title: String,
    val description: String? = null,
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val durationMinutes: Int? = null,
    val priority: Priority = Priority.NORMAL,
    val categoryName: String? = null,
    val reminderMinutes: Int? = Task.DEFAULT_REMINDER_MINUTES,
    val recurrence: RecurrenceDraft? = null,
    val notes: String? = null,
    /** AM/PM was inferred from working-hours heuristics rather than stated. */
    val meridiemAssumed: Boolean = false,
    /** The date came from an Urdu word that means both forwards and backwards ("kal"). */
    val dateDirectionAssumed: Boolean = false,
    /** No date was given at all, so "today or tomorrow" was inferred from the clock. */
    val dateInferred: Boolean = false,
) {
    val hasUsableTitle: Boolean get() = title.isNotBlank() && title.length >= 2
}
