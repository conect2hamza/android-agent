package com.personal.assistant.data.repository

import com.personal.assistant.core.transfer.ExportBundle
import com.personal.assistant.core.transfer.ExportCodec
import com.personal.assistant.core.transfer.ImportResult
import com.personal.assistant.data.dao.ActivityLogDao
import com.personal.assistant.data.dao.CategoryDao
import com.personal.assistant.data.dao.ChatDao
import com.personal.assistant.data.dao.MemoryDao
import com.personal.assistant.data.dao.PreferenceDao
import com.personal.assistant.data.dao.RecurrenceDao
import com.personal.assistant.data.dao.ReminderDao
import com.personal.assistant.data.dao.TaskDao
import com.personal.assistant.data.entity.PreferenceEntity
import com.personal.assistant.data.mapper.Mappers.toEntity
import com.personal.assistant.data.mapper.Mappers.toModel
import com.personal.assistant.notify.ReminderGateway
import com.personal.assistant.security.CryptoManager
import java.time.LocalDateTime

enum class ImportMode {
    /** Wipes local data first. What a restore-from-backup means. */
    REPLACE,

    /** Keeps what is here and adds the file's rows with fresh ids. */
    MERGE,
}

sealed interface ExportPayload {
    data class Json(val text: String) : ExportPayload
    data class Csv(val text: String) : ExportPayload
    data class EncryptedBackup(val text: String) : ExportPayload
}

/**
 * Export, import and encrypted backup.
 *
 * Everything produced here is handed back as text for the caller to write wherever the user chose. The
 * repository never touches shared storage or a share sheet itself, so there is no code path that can put
 * the user's data somewhere they did not pick.
 */
class BackupRepository(
    private val taskDao: TaskDao,
    private val logDao: ActivityLogDao,
    private val categoryDao: CategoryDao,
    private val recurrenceDao: RecurrenceDao,
    private val memoryDao: MemoryDao,
    private val chatDao: ChatDao,
    private val reminderDao: ReminderDao,
    private val preferenceDao: PreferenceDao,
    private val crypto: CryptoManager,
    private val reminders: ReminderGateway,
) {

    suspend fun bundle(includeChat: Boolean, now: LocalDateTime): ExportBundle = ExportBundle(
        exportedAt = now,
        categories = categoryDao.all().map { it.toModel() },
        recurrences = recurrenceDao.all().map { it.toModel() },
        tasks = taskDao.all().map { it.toModel() },
        activityLogs = logDao.all().map { it.toModel() },
        memories = memoryDao.all().map { it.toModel(crypto) },
        preferences = preferenceDao.all().associateBy({ it.key }, { it.value }),
        conversations = if (includeChat) chatDao.allConversations().map { it.toModel() } else emptyList(),
        messages = if (includeChat) chatDao.allMessages().map { it.toModel(crypto) } else emptyList(),
    )

    suspend fun exportJson(includeChat: Boolean, now: LocalDateTime): ExportPayload.Json =
        ExportPayload.Json(ExportCodec.encode(bundle(includeChat, now)))

    suspend fun exportTasksCsv(): ExportPayload.Csv =
        ExportPayload.Csv(ExportCodec.tasksToCsv(taskDao.all().map { it.toModel() }))

    /** A backup the user can store off-device, readable only with their passphrase. */
    suspend fun exportEncryptedBackup(
        passphrase: CharArray,
        includeChat: Boolean,
        now: LocalDateTime,
    ): ExportPayload.EncryptedBackup = ExportPayload.EncryptedBackup(
        crypto.encryptBackup(ExportCodec.encode(bundle(includeChat, now), pretty = false), passphrase),
    )

    /**
     * Reads a file without writing anything, so the UI can show the user what a restore would contain
     * before they commit to it.
     */
    fun inspect(fileText: String, passphrase: CharArray? = null): ImportResult {
        val text = if (crypto.isEncryptedBackup(fileText)) {
            val key = passphrase
                ?: return ImportResult(null, errors = listOf("This backup is encrypted. Enter its passphrase."))
            crypto.decryptBackup(fileText, key)
                ?: return ImportResult(null, errors = listOf("Wrong passphrase, or the backup file is damaged."))
        } else {
            fileText
        }
        return ExportCodec.decode(text)
    }

    /**
     * Applies a validated bundle.
     *
     * Ids are remapped rather than reused: a merge must not collide with existing rows, and even a
     * replace is safer with fresh ids than trusting numbers from a file. The mapping is what keeps
     * activity logs attached to the right task afterwards.
     */
    suspend fun restore(bundle: ExportBundle, mode: ImportMode, now: LocalDateTime): ImportResult {
        if (mode == ImportMode.REPLACE) {
            reminderDao.deleteAll()
            logDao.deleteAll()
            taskDao.deleteAll()
            memoryDao.deleteAll()
            chatDao.clearHistory()
        }

        val categoryIds = mutableMapOf<Long, Long>()
        for (category in bundle.categories) {
            val existing = categoryDao.byName(category.name)
            val id = existing?.id ?: categoryDao.upsert(category.copy(id = 0).toEntity())
            categoryIds[category.id] = id
        }

        val recurrenceIds = mutableMapOf<Long, Long>()
        for (recurrence in bundle.recurrences) {
            recurrenceIds[recurrence.id] = recurrenceDao.upsert(recurrence.copy(id = 0).toEntity())
        }

        val taskIds = mutableMapOf<Long, Long>()
        for (task in bundle.tasks) {
            val inserted = taskDao.insert(
                task.copy(
                    id = 0,
                    categoryId = task.categoryId?.let { categoryIds[it] },
                    recurrenceId = task.recurrenceId?.let { recurrenceIds[it] },
                ).toEntity(),
            )
            taskIds[task.id] = inserted
        }

        logDao.insertAll(
            bundle.activityLogs.mapNotNull { log ->
                val mapped = log.taskId?.let { taskIds[it] }
                // A log whose task did not survive the remap would be an orphan row.
                if (log.taskId != null && mapped == null) null
                else log.copy(id = 0, taskId = mapped).toEntity()
            },
        )

        memoryDao.insertAll(bundle.memories.map { it.copy(id = 0).toEntity(crypto) })

        if (bundle.conversations.isNotEmpty()) {
            val conversationIds = mutableMapOf<Long, Long>()
            for (conversation in bundle.conversations) {
                conversationIds[conversation.id] =
                    chatDao.insertConversation(conversation.copy(id = 0).toEntity())
            }
            chatDao.insertMessages(
                bundle.messages.mapNotNull { message ->
                    val mapped = conversationIds[message.conversationId] ?: return@mapNotNull null
                    message.copy(
                        id = 0,
                        conversationId = mapped,
                        relatedTaskId = message.relatedTaskId?.let { taskIds[it] },
                    ).toEntity(crypto)
                },
            )
        }

        if (bundle.preferences.isNotEmpty()) {
            preferenceDao.putAll(bundle.preferences.map { (key, value) -> PreferenceEntity(key, value) })
        }

        // Imported tasks have no alarms until the whole schedule is rebuilt.
        reminders.rescheduleAll()
        reminders.rescheduleSummaries()

        return ImportResult(bundle, warnings = listOf("Imported ${bundle.tasks.size} tasks"))
    }

    /** Deletes everything, for the "delete all data" action in settings. */
    suspend fun deleteEverything() {
        reminderDao.deleteAll()
        logDao.deleteAll()
        taskDao.deleteAll()
        memoryDao.deleteAll()
        chatDao.clearHistory()
        reminders.rescheduleAll()
    }

    /** Rough on-disk footprint, for the storage screen. */
    suspend fun storageEstimate(): Map<String, Int> = mapOf(
        "Tasks" to taskDao.all().size,
        "Activity records" to logDao.all().size,
        "Memories" to memoryDao.all().size,
        "Chat messages" to chatDao.allMessages().size,
    )
}
