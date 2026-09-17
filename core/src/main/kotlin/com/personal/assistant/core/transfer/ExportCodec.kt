package com.personal.assistant.core.transfer

import com.personal.assistant.core.model.ActivityEvent
import com.personal.assistant.core.model.ActivityLog
import com.personal.assistant.core.model.Category
import com.personal.assistant.core.model.Conversation
import com.personal.assistant.core.model.Memory
import com.personal.assistant.core.model.MemoryCategory
import com.personal.assistant.core.model.Message
import com.personal.assistant.core.model.Priority
import com.personal.assistant.core.model.Recurrence
import com.personal.assistant.core.model.RecurrenceFrequency
import com.personal.assistant.core.model.Sender
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.core.util.JsonValue
import com.personal.assistant.core.util.MiniJson
import com.personal.assistant.core.util.array
import com.personal.assistant.core.util.bool
import com.personal.assistant.core.util.int
import com.personal.assistant.core.util.json
import com.personal.assistant.core.util.jsonOf
import com.personal.assistant.core.util.long
import com.personal.assistant.core.util.obj
import com.personal.assistant.core.util.string
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Reads and writes the JSON export format, and a flat CSV for spreadsheets. */
object ExportCodec {

    // -------------------------------------------------------------------- write

    fun encode(bundle: ExportBundle, pretty: Boolean = true): String {
        val json = jsonOf(
            "version" to bundle.version.json(),
            "exported_at" to bundle.exportedAt.toString().json(),
            "categories" to bundle.categories.map(::encodeCategory).json(),
            "recurrences" to bundle.recurrences.map(::encodeRecurrence).json(),
            "tasks" to bundle.tasks.map(::encodeTask).json(),
            "activity_logs" to bundle.activityLogs.map(::encodeLog).json(),
            "memories" to bundle.memories.map(::encodeMemory).json(),
            "preferences" to JsonValue.Obj(
                bundle.preferences.mapValues { (_, value) -> JsonValue.Str(value) },
            ),
            "conversations" to bundle.conversations.map(::encodeConversation).json(),
            "messages" to bundle.messages.map(::encodeMessage).json(),
        )
        return MiniJson.write(json, indent = if (pretty) 2 else 0)
    }

    private fun encodeCategory(category: Category) = jsonOf(
        "id" to category.id.json(),
        "name" to category.name.json(),
        "color" to category.colorArgb.json(),
        "built_in" to category.builtIn.json(),
    )

    private fun encodeRecurrence(recurrence: Recurrence) = jsonOf(
        "id" to recurrence.id.json(),
        "frequency" to recurrence.frequency.name.json(),
        "interval" to recurrence.interval.json(),
        "days_of_week" to recurrence.daysOfWeek.map { JsonValue.Str(it.name) }.json(),
        "day_of_month" to recurrence.dayOfMonth.json(),
        "start_date" to recurrence.startDate.toString().json(),
        "end_date" to recurrence.endDate?.toString().json(),
        "occurrence_limit" to recurrence.occurrenceLimit.json(),
    )

    private fun encodeTask(task: Task) = jsonOf(
        "id" to task.id.json(),
        "title" to task.title.json(),
        "description" to task.description.json(),
        "date" to task.date.toString().json(),
        "start_time" to task.startTime?.toString().json(),
        "end_time" to task.endTime?.toString().json(),
        "duration_minutes" to task.durationMinutes.json(),
        "priority" to task.priority.name.json(),
        "category_id" to task.categoryId.json(),
        "status" to task.status.name.json(),
        "progress" to task.progress.json(),
        "reminder_minutes" to task.reminderMinutes.json(),
        "notes" to task.notes.json(),
        "recurrence_id" to task.recurrenceId.json(),
        "series_id" to task.seriesId.json(),
        "tracking_enabled" to task.trackingEnabled.json(),
        "created_at" to task.createdAt.toString().json(),
        "updated_at" to task.updatedAt.toString().json(),
        "completed_at" to task.completedAt?.toString().json(),
    )

    private fun encodeLog(log: ActivityLog) = jsonOf(
        "id" to log.id.json(),
        "task_id" to log.taskId.json(),
        "event" to log.event.name.json(),
        "timestamp" to log.timestamp.toString().json(),
        "duration_minutes" to log.durationMinutes.json(),
        "notes" to log.notes.json(),
    )

    private fun encodeMemory(memory: Memory) = jsonOf(
        "id" to memory.id.json(),
        "content" to memory.content.json(),
        "category" to memory.category.name.json(),
        "importance" to memory.importance.json(),
        "created_at" to memory.createdAt.toString().json(),
        "updated_at" to memory.updatedAt.toString().json(),
    )

    private fun encodeConversation(conversation: Conversation) = jsonOf(
        "id" to conversation.id.json(),
        "title" to conversation.title.json(),
        "created_at" to conversation.createdAt.toString().json(),
        "updated_at" to conversation.updatedAt.toString().json(),
    )

    private fun encodeMessage(message: Message) = jsonOf(
        "id" to message.id.json(),
        "conversation_id" to message.conversationId.json(),
        "sender" to message.sender.name.json(),
        "content" to message.content.json(),
        "timestamp" to message.timestamp.toString().json(),
        "related_task_id" to message.relatedTaskId.json(),
    )

    // --------------------------------------------------------------------- read

    fun decode(text: String): ImportResult {
        val root = runCatching { MiniJson.parse(text) }.getOrElse { error ->
            return ImportResult(null, errors = listOf("File is not valid JSON: ${error.message}"))
        } as? JsonValue.Obj
            ?: return ImportResult(null, errors = listOf("Expected a JSON object at the top level"))

        val version = root.int("version")
            ?: return ImportResult(null, errors = listOf("Missing export version"))
        if (version < ExportBundle.MINIMUM_SUPPORTED_VERSION) {
            return ImportResult(null, errors = listOf("Export version $version is too old to read"))
        }
        if (version > ExportBundle.CURRENT_VERSION) {
            return ImportResult(
                null,
                errors = listOf(
                    "Export version $version was written by a newer version of the app. " +
                        "Update the app before restoring this backup.",
                ),
            )
        }

        val warnings = mutableListOf<String>()
        val exportedAt = parseDateTime(root.string("exported_at"))
            ?: LocalDateTime.now().also { warnings += "Export timestamp missing; using now" }

        val categories = root.array("categories").mapIndexedNotNull { index, entry ->
            val obj = entry as? JsonValue.Obj ?: return@mapIndexedNotNull skip(warnings, "category", index)
            val name = obj.string("name") ?: return@mapIndexedNotNull skip(warnings, "category", index)
            Category(
                id = obj.long("id") ?: 0L,
                name = name.take(60),
                colorArgb = obj.int("color") ?: Category.DEFAULTS.last().colorArgb,
                builtIn = obj.bool("built_in") ?: false,
            )
        }

        val recurrences = root.array("recurrences").mapIndexedNotNull { index, entry ->
            val obj = entry as? JsonValue.Obj ?: return@mapIndexedNotNull skip(warnings, "recurrence", index)
            val frequency = obj.string("frequency")?.let { name ->
                RecurrenceFrequency.entries.firstOrNull { it.name == name.uppercase() }
            } ?: return@mapIndexedNotNull skip(warnings, "recurrence", index)
            val startDate = parseDate(obj.string("start_date"))
                ?: return@mapIndexedNotNull skip(warnings, "recurrence", index)
            runCatching {
                Recurrence(
                    id = obj.long("id") ?: 0L,
                    frequency = frequency,
                    interval = (obj.int("interval") ?: 1).coerceIn(1, 52),
                    daysOfWeek = obj.array("days_of_week")
                        .mapNotNull { (it as? JsonValue.Str)?.value?.uppercase() }
                        .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                        .toSet(),
                    dayOfMonth = obj.int("day_of_month")?.takeIf { it in 1..31 },
                    startDate = startDate,
                    endDate = parseDate(obj.string("end_date")),
                    occurrenceLimit = obj.int("occurrence_limit")?.takeIf { it > 0 },
                )
            }.getOrElse { skip(warnings, "recurrence", index) }
        }

        val tasks = root.array("tasks").mapIndexedNotNull { index, entry ->
            val obj = entry as? JsonValue.Obj ?: return@mapIndexedNotNull skip(warnings, "task", index)
            val title = obj.string("title") ?: return@mapIndexedNotNull skip(warnings, "task", index)
            val date = parseDate(obj.string("date")) ?: return@mapIndexedNotNull skip(warnings, "task", index)
            val created = parseDateTime(obj.string("created_at")) ?: exportedAt
            runCatching {
                Task(
                    id = obj.long("id") ?: 0L,
                    title = title.take(200),
                    description = obj.string("description")?.take(2000),
                    date = date,
                    startTime = parseTime(obj.string("start_time")),
                    endTime = parseTime(obj.string("end_time")),
                    durationMinutes = obj.int("duration_minutes")?.takeIf { it in 1..(24 * 60) },
                    priority = Priority.fromNameOrNull(obj.string("priority")) ?: Priority.NORMAL,
                    categoryId = obj.long("category_id"),
                    status = TaskStatus.fromNameOrNull(obj.string("status")) ?: TaskStatus.PLANNED,
                    // Clamped rather than rejected: a bad percentage is not worth losing the task over.
                    progress = (obj.int("progress") ?: 0).coerceIn(0, 100),
                    reminderMinutes = obj.int("reminder_minutes")?.takeIf { it in 0..(24 * 60) },
                    notes = obj.string("notes")?.take(2000),
                    recurrenceId = obj.long("recurrence_id"),
                    seriesId = obj.long("series_id"),
                    trackingEnabled = obj.bool("tracking_enabled") ?: true,
                    createdAt = created,
                    updatedAt = parseDateTime(obj.string("updated_at")) ?: created,
                    completedAt = parseDateTime(obj.string("completed_at")),
                )
            }.getOrElse { skip(warnings, "task", index) }
        }

        val knownTaskIds = tasks.map { it.id }.toSet()
        val logs = root.array("activity_logs").mapIndexedNotNull { index, entry ->
            val obj = entry as? JsonValue.Obj ?: return@mapIndexedNotNull skip(warnings, "activity log", index)
            val event = obj.string("event")?.let { name ->
                ActivityEvent.entries.firstOrNull { it.name == name.uppercase() }
            } ?: return@mapIndexedNotNull skip(warnings, "activity log", index)
            val timestamp = parseDateTime(obj.string("timestamp"))
                ?: return@mapIndexedNotNull skip(warnings, "activity log", index)
            val taskId = obj.long("task_id")
            // A log pointing at a task that is not in the file would be an orphan row after import.
            if (taskId != null && taskId !in knownTaskIds) {
                warnings += "Skipped activity log $index: no matching task"
                return@mapIndexedNotNull null
            }
            ActivityLog(
                id = obj.long("id") ?: 0L,
                taskId = taskId,
                event = event,
                timestamp = timestamp,
                durationMinutes = obj.int("duration_minutes")?.takeIf { it >= 0 },
                notes = obj.string("notes")?.take(1000),
            )
        }

        val memories = root.array("memories").mapIndexedNotNull { index, entry ->
            val obj = entry as? JsonValue.Obj ?: return@mapIndexedNotNull skip(warnings, "memory", index)
            val content = obj.string("content") ?: return@mapIndexedNotNull skip(warnings, "memory", index)
            val created = parseDateTime(obj.string("created_at")) ?: exportedAt
            Memory(
                id = obj.long("id") ?: 0L,
                content = content.take(1000),
                category = MemoryCategory.fromNameOrNull(obj.string("category")) ?: MemoryCategory.NOTE,
                importance = (obj.int("importance") ?: 3).coerceIn(1, 5),
                createdAt = created,
                updatedAt = parseDateTime(obj.string("updated_at")) ?: created,
            )
        }

        val preferences = root.obj("preferences")?.entries
            ?.mapNotNull { (key, value) -> (value as? JsonValue.Str)?.let { key to it.value } }
            ?.toMap()
            ?: emptyMap()

        val conversations = root.array("conversations").mapIndexedNotNull { index, entry ->
            val obj = entry as? JsonValue.Obj ?: return@mapIndexedNotNull skip(warnings, "conversation", index)
            val created = parseDateTime(obj.string("created_at")) ?: exportedAt
            Conversation(
                id = obj.long("id") ?: 0L,
                title = obj.string("title")?.take(120),
                createdAt = created,
                updatedAt = parseDateTime(obj.string("updated_at")) ?: created,
            )
        }

        val knownConversationIds = conversations.map { it.id }.toSet()
        val messages = root.array("messages").mapIndexedNotNull { index, entry ->
            val obj = entry as? JsonValue.Obj ?: return@mapIndexedNotNull skip(warnings, "message", index)
            val conversationId = obj.long("conversation_id")
                ?: return@mapIndexedNotNull skip(warnings, "message", index)
            if (knownConversationIds.isNotEmpty() && conversationId !in knownConversationIds) {
                warnings += "Skipped message $index: no matching conversation"
                return@mapIndexedNotNull null
            }
            val sender = obj.string("sender")?.let { name ->
                Sender.entries.firstOrNull { it.name == name.uppercase() }
            } ?: return@mapIndexedNotNull skip(warnings, "message", index)
            val timestamp = parseDateTime(obj.string("timestamp"))
                ?: return@mapIndexedNotNull skip(warnings, "message", index)
            Message(
                id = obj.long("id") ?: 0L,
                conversationId = conversationId,
                sender = sender,
                content = obj.string("content")?.take(4000) ?: "",
                timestamp = timestamp,
                relatedTaskId = obj.long("related_task_id"),
            )
        }

        return ImportResult(
            bundle = ExportBundle(
                version = version,
                exportedAt = exportedAt,
                categories = categories,
                recurrences = recurrences,
                tasks = tasks,
                activityLogs = logs,
                memories = memories,
                preferences = preferences,
                conversations = conversations,
                messages = messages,
            ),
            warnings = warnings,
        )
    }

    // ---------------------------------------------------------------------- csv

    private const val CSV_HEADER =
        "id,title,date,start_time,end_time,duration_minutes,priority,status,progress,category_id,notes"

    fun tasksToCsv(tasks: List<Task>): String = buildString {
        appendLine(CSV_HEADER)
        for (task in tasks) {
            appendLine(
                listOf(
                    task.id.toString(),
                    task.title,
                    task.date.toString(),
                    task.startTime?.toString() ?: "",
                    task.endTime?.toString() ?: "",
                    task.durationMinutes?.toString() ?: "",
                    task.priority.name,
                    task.status.name,
                    task.progress.toString(),
                    task.categoryId?.toString() ?: "",
                    task.notes ?: "",
                ).joinToString(",") { csvCell(it) },
            )
        }
    }

    /**
     * Quotes a CSV cell, and neutralises values a spreadsheet would execute. A title beginning with
     * `=` is data, not a formula, and it must not become one just because the user exported it.
     */
    private fun csvCell(value: String): String {
        val sanitised = if (value.firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value
        val needsQuotes = sanitised.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = sanitised.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    // ------------------------------------------------------------------ parsing

    private fun <T> skip(warnings: MutableList<String>, what: String, index: Int): T? {
        warnings += "Skipped invalid $what at position $index"
        return null
    }

    private fun parseDate(text: String?): LocalDate? = text?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseTime(text: String?): LocalTime? = text?.let {
        try {
            LocalTime.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseDateTime(text: String?): LocalDateTime? = text?.let {
        try {
            LocalDateTime.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
