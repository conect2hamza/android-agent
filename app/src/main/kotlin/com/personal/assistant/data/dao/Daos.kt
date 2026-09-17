package com.personal.assistant.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.personal.assistant.data.entity.ActivityLogEntity
import com.personal.assistant.data.entity.CategoryEntity
import com.personal.assistant.data.entity.ConversationEntity
import com.personal.assistant.data.entity.MemoryEntity
import com.personal.assistant.data.entity.MessageEntity
import com.personal.assistant.data.entity.PreferenceEntity
import com.personal.assistant.data.entity.RecurrenceEntity
import com.personal.assistant.data.entity.ReminderEntity
import com.personal.assistant.data.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY name")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Upsert
    suspend fun upsert(category: CategoryEntity): Long

    @Delete
    suspend fun delete(category: CategoryEntity)
}

@Dao
interface RecurrenceDao {
    @Query("SELECT * FROM recurrences WHERE id = :id")
    suspend fun byId(id: Long): RecurrenceEntity?

    @Query("SELECT * FROM recurrences")
    suspend fun all(): List<RecurrenceEntity>

    @Upsert
    suspend fun upsert(recurrence: RecurrenceEntity): Long

    @Query("DELETE FROM recurrences WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE dateEpochDay = :epochDay ORDER BY startMinuteOfDay IS NULL, startMinuteOfDay, id")
    fun observeForDay(epochDay: Long): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay
        ORDER BY dateEpochDay, startMinuteOfDay IS NULL, startMinuteOfDay, id
        """,
    )
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<TaskEntity>>

    @Query(
        """
        SELECT * FROM tasks
        WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay
        ORDER BY dateEpochDay, startMinuteOfDay IS NULL, startMinuteOfDay, id
        """,
    )
    suspend fun range(fromEpochDay: Long, toEpochDay: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks ORDER BY dateEpochDay DESC, id DESC")
    suspend fun all(): List<TaskEntity>

    /**
     * Recent titles for resolving spoken references and for the model prompt. Capped, because a longer
     * list costs prompt tokens without improving the match.
     */
    @Query("SELECT * FROM tasks ORDER BY updatedAtEpochSecond DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TaskEntity>

    /**
     * Candidate matches for "my website task". `LIKE` with an escaped pattern is enough here: the
     * table is one person's task list, and it avoids shipping an FTS index that would have to be kept
     * in sync and encrypted separately.
     */
    @Query(
        """
        SELECT * FROM tasks
        WHERE title LIKE '%' || :needle || '%' COLLATE NOCASE
           OR notes LIKE '%' || :needle || '%' COLLATE NOCASE
           OR description LIKE '%' || :needle || '%' COLLATE NOCASE
        ORDER BY dateEpochDay DESC
        LIMIT :limit
        """,
    )
    suspend fun search(needle: String, limit: Int): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status IN (:statuses) AND dateEpochDay <= :throughEpochDay")
    suspend fun withStatusUpTo(statuses: List<String>, throughEpochDay: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE seriesId = :seriesId ORDER BY dateEpochDay")
    suspend fun bySeries(seriesId: Long): List<TaskEntity>

    @Query("SELECT MAX(seriesId) FROM tasks")
    suspend fun maxSeriesId(): Long?

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Insert
    suspend fun insertAll(tasks: List<TaskEntity>): List<Long>

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks SET status = :status, updatedAtEpochSecond = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, updatedAt: Long)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tasks WHERE seriesId = :seriesId AND dateEpochDay >= :fromEpochDay")
    suspend fun deleteSeriesFrom(seriesId: Long, fromEpochDay: Long)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
}

@Dao
interface ActivityLogDao {
    @Query("SELECT * FROM activity_logs WHERE taskId = :taskId ORDER BY timestampEpochSecond")
    suspend fun forTask(taskId: Long): List<ActivityLogEntity>

    @Query(
        """
        SELECT * FROM activity_logs
        WHERE timestampEpochSecond BETWEEN :fromEpochSecond AND :toEpochSecond
        ORDER BY timestampEpochSecond
        """,
    )
    suspend fun inRange(fromEpochSecond: Long, toEpochSecond: Long): List<ActivityLogEntity>

    @Query(
        """
        SELECT * FROM activity_logs
        WHERE timestampEpochSecond BETWEEN :fromEpochSecond AND :toEpochSecond
        ORDER BY timestampEpochSecond
        """,
    )
    fun observeRange(fromEpochSecond: Long, toEpochSecond: Long): Flow<List<ActivityLogEntity>>

    @Query("SELECT * FROM activity_logs ORDER BY timestampEpochSecond")
    suspend fun all(): List<ActivityLogEntity>

    @Insert
    suspend fun insert(log: ActivityLogEntity): Long

    @Insert
    suspend fun insertAll(logs: List<ActivityLogEntity>)

    @Query("DELETE FROM activity_logs")
    suspend fun deleteAll()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAtEpochSecond DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAtEpochSecond DESC LIMIT :limit")
    suspend fun top(limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories")
    suspend fun all(): List<MemoryEntity>

    @Upsert
    suspend fun upsert(memory: MemoryEntity): Long

    @Insert
    suspend fun insertAll(memories: List<MemoryEntity>)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtEpochSecond DESC LIMIT 1")
    suspend fun latestConversation(): ConversationEntity?

    @Query("SELECT * FROM conversations")
    suspend fun allConversations(): List<ConversationEntity>

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    /** Bumps only the activity stamp; a full @Update would overwrite createdAt. */
    @Query("UPDATE conversations SET updatedAtEpochSecond = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: Long, updatedAt: Long)

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestampEpochSecond DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeMessages(conversationId: Long, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestampEpochSecond")
    suspend fun allMessages(): List<MessageEntity>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: Long)

    /** Messages cascade from the foreign key, so this is the whole of clearing chat history. */
    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE fired = 0 ORDER BY triggerAtEpochSecond")
    suspend fun pending(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE taskId = :taskId")
    suspend fun forTask(taskId: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE requestCode = :requestCode LIMIT 1")
    suspend fun byRequestCode(requestCode: Int): ReminderEntity?

    @Insert
    suspend fun insertAll(reminders: List<ReminderEntity>)

    @Query("UPDATE reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: Long)

    @Query("DELETE FROM reminders WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()
}

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preferences")
    fun observeAll(): Flow<List<PreferenceEntity>>

    @Query("SELECT * FROM preferences")
    suspend fun all(): List<PreferenceEntity>

    @Query("SELECT value FROM preferences WHERE `key` = :key")
    suspend fun value(key: String): String?

    @Upsert
    suspend fun put(preference: PreferenceEntity)

    @Upsert
    suspend fun putAll(preferences: List<PreferenceEntity>)

    @Query("DELETE FROM preferences WHERE `key` = :key")
    suspend fun remove(key: String)
}
