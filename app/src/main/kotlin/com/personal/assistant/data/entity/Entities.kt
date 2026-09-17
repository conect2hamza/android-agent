package com.personal.assistant.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room rows.
 *
 * Dates and times are stored as integers rather than formatted strings -- a date as an epoch day and
 * a time as minutes past midnight. That keeps range queries (`WHERE date BETWEEN ...`) index-friendly
 * and sidesteps every text-collation and time-zone question a string column would invite.
 *
 * Entities are kept separate from the domain model in :core on purpose: the storage shape can change
 * for a migration without the parser or the report engine noticing.
 */
@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Int,
    val builtIn: Boolean = false,
)

@Entity(tableName = "recurrences")
data class RecurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val frequency: String,
    val interval: Int,
    /** Comma-separated day names; empty for daily and monthly rules. */
    val daysOfWeek: String,
    val dayOfMonth: Int?,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long?,
    val occurrenceLimit: Int?,
)

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            // Deleting a category must not delete the user's work; the tasks simply become unfiled.
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = RecurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["recurrenceId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["dateEpochDay"]),
        Index(value = ["status"]),
        Index(value = ["categoryId"]),
        Index(value = ["recurrenceId"]),
        Index(value = ["seriesId", "dateEpochDay"]),
    ],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    @ColumnInfo(name = "dateEpochDay") val dateEpochDay: Long,
    /** Minutes past midnight, or null for an all-day task. */
    val startMinuteOfDay: Int? = null,
    val endMinuteOfDay: Int? = null,
    val durationMinutes: Int? = null,
    val priority: String,
    val categoryId: Long? = null,
    val status: String,
    val progress: Int = 0,
    val reminderMinutes: Int? = null,
    val notes: String? = null,
    val recurrenceId: Long? = null,
    /** Groups the generated occurrences of one recurring series. */
    val seriesId: Long? = null,
    val trackingEnabled: Boolean = true,
    val createdAtEpochSecond: Long,
    val updatedAtEpochSecond: Long,
    val completedAtEpochSecond: Long? = null,
)

@Entity(
    tableName = "activity_logs",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["taskId"]), Index(value = ["timestampEpochSecond"])],
)
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    val event: String,
    val timestampEpochSecond: Long,
    val durationMinutes: Int? = null,
    val notes: String? = null,
)

@Entity(tableName = "memories", indices = [Index(value = ["category"])])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stored encrypted; see CryptoManager. */
    val content: String,
    val category: String,
    val importance: Int,
    val createdAtEpochSecond: Long,
    val updatedAtEpochSecond: Long,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String? = null,
    val createdAtEpochSecond: Long,
    val updatedAtEpochSecond: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId", "timestampEpochSecond"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val sender: String,
    /** Stored encrypted; see CryptoManager. */
    val content: String,
    val timestampEpochSecond: Long,
    val relatedTaskId: Long? = null,
    val awaitingConfirmation: Boolean = false,
)

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["taskId"]), Index(value = ["triggerAtEpochSecond"]), Index(value = ["fired"])],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    val kind: String,
    val triggerAtEpochSecond: Long,
    val fired: Boolean = false,
    val requestCode: Int,
)

@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
)
