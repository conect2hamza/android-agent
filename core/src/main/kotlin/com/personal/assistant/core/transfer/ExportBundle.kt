package com.personal.assistant.core.transfer

import com.personal.assistant.core.model.ActivityLog
import com.personal.assistant.core.model.Category
import com.personal.assistant.core.model.Conversation
import com.personal.assistant.core.model.Memory
import com.personal.assistant.core.model.Message
import com.personal.assistant.core.model.Recurrence
import com.personal.assistant.core.model.Task
import java.time.LocalDateTime

/**
 * Everything the user can take out of the app, in one structure.
 *
 * Chat history is separated from the rest so an export can include tasks and reports without the
 * conversation that produced them.
 */
data class ExportBundle(
    val version: Int = CURRENT_VERSION,
    val exportedAt: LocalDateTime,
    val categories: List<Category> = emptyList(),
    val recurrences: List<Recurrence> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val activityLogs: List<ActivityLog> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val preferences: Map<String, String> = emptyMap(),
    val conversations: List<Conversation> = emptyList(),
    val messages: List<Message> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1

        /** Older files are read; newer ones are refused rather than half-understood. */
        const val MINIMUM_SUPPORTED_VERSION = 1
    }
}

/**
 * Outcome of reading an import file.
 *
 * Rows that fail validation are dropped with a warning rather than aborting the whole restore: one
 * corrupt task should not cost the user a year of history. Anything that makes the file as a whole
 * untrustworthy -- wrong shape, unsupported version -- is an error, and nothing is imported.
 */
data class ImportResult(
    val bundle: ExportBundle?,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val succeeded: Boolean get() = bundle != null && errors.isEmpty()

    fun summary(): String = when {
        bundle == null -> "Import failed: ${errors.firstOrNull() ?: "unreadable file"}"
        else -> buildString {
            append("${bundle.tasks.size} tasks, ${bundle.memories.size} memories, ")
            append("${bundle.activityLogs.size} activity records")
            if (warnings.isNotEmpty()) append(" (${warnings.size} skipped)")
        }
    }
}
