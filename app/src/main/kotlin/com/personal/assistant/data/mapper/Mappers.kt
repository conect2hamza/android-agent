package com.personal.assistant.data.mapper

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
import com.personal.assistant.core.model.Reminder
import com.personal.assistant.core.model.ReminderKind
import com.personal.assistant.core.model.Sender
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.data.entity.ActivityLogEntity
import com.personal.assistant.data.entity.CategoryEntity
import com.personal.assistant.data.entity.ConversationEntity
import com.personal.assistant.data.entity.MemoryEntity
import com.personal.assistant.data.entity.MessageEntity
import com.personal.assistant.data.entity.RecurrenceEntity
import com.personal.assistant.data.entity.ReminderEntity
import com.personal.assistant.data.entity.TaskEntity
import com.personal.assistant.security.CryptoManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Translation between Room rows and the domain model.
 *
 * Timestamps are converted through the device's *current* zone rather than UTC. A personal planner is
 * about wall-clock time: "4 PM tomorrow" should stay 4 PM after the user flies somewhere, and the epoch
 * second is only ever an implementation detail of how the row is ordered.
 */
object Mappers {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun LocalDateTime.toEpochSecond(): Long = atZone(zone).toEpochSecond()

    fun Long.toLocalDateTime(): LocalDateTime =
        java.time.Instant.ofEpochSecond(this).atZone(zone).toLocalDateTime()

    // ---------------------------------------------------------------- category

    fun CategoryEntity.toModel() = Category(id = id, name = name, colorArgb = colorArgb, builtIn = builtIn)

    fun Category.toEntity() = CategoryEntity(id = id, name = name, colorArgb = colorArgb, builtIn = builtIn)

    // -------------------------------------------------------------- recurrence

    fun RecurrenceEntity.toModel(): Recurrence = Recurrence(
        id = id,
        frequency = RecurrenceFrequency.entries.firstOrNull { it.name == frequency }
            ?: RecurrenceFrequency.DAILY,
        interval = interval.coerceAtLeast(1),
        daysOfWeek = daysOfWeek.split(',')
            .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name.trim() } }
            .toSet(),
        dayOfMonth = dayOfMonth,
        startDate = LocalDate.ofEpochDay(startDateEpochDay),
        endDate = endDateEpochDay?.let { LocalDate.ofEpochDay(it) },
        occurrenceLimit = occurrenceLimit,
    )

    fun Recurrence.toEntity() = RecurrenceEntity(
        id = id,
        frequency = frequency.name,
        interval = interval,
        daysOfWeek = daysOfWeek.joinToString(",") { it.name },
        dayOfMonth = dayOfMonth,
        startDateEpochDay = startDate.toEpochDay(),
        endDateEpochDay = endDate?.toEpochDay(),
        occurrenceLimit = occurrenceLimit,
    )

    // -------------------------------------------------------------------- task

    fun TaskEntity.toModel(): Task = Task(
        id = id,
        title = title,
        description = description,
        date = LocalDate.ofEpochDay(dateEpochDay),
        startTime = startMinuteOfDay?.let { LocalTime.ofSecondOfDay(it * 60L) },
        endTime = endMinuteOfDay?.let { LocalTime.ofSecondOfDay(it * 60L) },
        durationMinutes = durationMinutes,
        priority = Priority.fromNameOrNull(priority) ?: Priority.NORMAL,
        categoryId = categoryId,
        status = TaskStatus.fromNameOrNull(status) ?: TaskStatus.PLANNED,
        progress = progress.coerceIn(0, 100),
        reminderMinutes = reminderMinutes,
        notes = notes,
        recurrenceId = recurrenceId,
        seriesId = seriesId,
        trackingEnabled = trackingEnabled,
        createdAt = createdAtEpochSecond.toLocalDateTime(),
        updatedAt = updatedAtEpochSecond.toLocalDateTime(),
        completedAt = completedAtEpochSecond?.toLocalDateTime(),
    )

    fun Task.toEntity() = TaskEntity(
        id = id,
        title = title,
        description = description,
        dateEpochDay = date.toEpochDay(),
        startMinuteOfDay = startTime?.let { it.hour * 60 + it.minute },
        endMinuteOfDay = endTime?.let { it.hour * 60 + it.minute },
        durationMinutes = durationMinutes,
        priority = priority.name,
        categoryId = categoryId,
        status = status.name,
        progress = progress,
        reminderMinutes = reminderMinutes,
        notes = notes,
        recurrenceId = recurrenceId,
        seriesId = seriesId,
        trackingEnabled = trackingEnabled,
        createdAtEpochSecond = createdAt.toEpochSecond(),
        updatedAtEpochSecond = updatedAt.toEpochSecond(),
        completedAtEpochSecond = completedAt?.toEpochSecond(),
    )

    // ------------------------------------------------------------ activity log

    fun ActivityLogEntity.toModel() = ActivityLog(
        id = id,
        taskId = taskId,
        event = ActivityEvent.entries.firstOrNull { it.name == event } ?: ActivityEvent.NOTE_ADDED,
        timestamp = timestampEpochSecond.toLocalDateTime(),
        durationMinutes = durationMinutes,
        notes = notes,
    )

    fun ActivityLog.toEntity() = ActivityLogEntity(
        id = id,
        taskId = taskId,
        event = event.name,
        timestampEpochSecond = timestamp.toEpochSecond(),
        durationMinutes = durationMinutes,
        notes = notes,
    )

    // ------------------------------------------------------------------ memory

    fun MemoryEntity.toModel(crypto: CryptoManager) = Memory(
        id = id,
        content = crypto.decrypt(content),
        category = MemoryCategory.fromNameOrNull(category) ?: MemoryCategory.NOTE,
        importance = importance.coerceIn(1, 5),
        createdAt = createdAtEpochSecond.toLocalDateTime(),
        updatedAt = updatedAtEpochSecond.toLocalDateTime(),
    )

    fun Memory.toEntity(crypto: CryptoManager) = MemoryEntity(
        id = id,
        content = crypto.encrypt(content),
        category = category.name,
        importance = importance,
        createdAtEpochSecond = createdAt.toEpochSecond(),
        updatedAtEpochSecond = updatedAt.toEpochSecond(),
    )

    // -------------------------------------------------------------------- chat

    fun ConversationEntity.toModel() = Conversation(
        id = id,
        title = title,
        createdAt = createdAtEpochSecond.toLocalDateTime(),
        updatedAt = updatedAtEpochSecond.toLocalDateTime(),
    )

    fun Conversation.toEntity() = ConversationEntity(
        id = id,
        title = title,
        createdAtEpochSecond = createdAt.toEpochSecond(),
        updatedAtEpochSecond = updatedAt.toEpochSecond(),
    )

    fun MessageEntity.toModel(crypto: CryptoManager) = Message(
        id = id,
        conversationId = conversationId,
        sender = Sender.entries.firstOrNull { it.name == sender } ?: Sender.SYSTEM,
        content = crypto.decrypt(content),
        timestamp = timestampEpochSecond.toLocalDateTime(),
        relatedTaskId = relatedTaskId,
        awaitingConfirmation = awaitingConfirmation,
    )

    fun Message.toEntity(crypto: CryptoManager) = MessageEntity(
        id = id,
        conversationId = conversationId,
        sender = sender.name,
        content = crypto.encrypt(content),
        timestampEpochSecond = timestamp.toEpochSecond(),
        relatedTaskId = relatedTaskId,
        awaitingConfirmation = awaitingConfirmation,
    )

    // ---------------------------------------------------------------- reminder

    fun ReminderEntity.toModel() = Reminder(
        id = id,
        taskId = taskId,
        kind = ReminderKind.entries.firstOrNull { it.name == kind } ?: ReminderKind.LEAD_UP,
        triggerAt = triggerAtEpochSecond.toLocalDateTime(),
        fired = fired,
        requestCode = requestCode,
    )

    fun Reminder.toEntity() = ReminderEntity(
        id = id,
        taskId = taskId,
        kind = kind.name,
        triggerAtEpochSecond = triggerAt.toEpochSecond(),
        fired = fired,
        requestCode = requestCode,
    )
}
