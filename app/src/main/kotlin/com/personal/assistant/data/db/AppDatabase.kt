package com.personal.assistant.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.assistant.data.dao.ActivityLogDao
import com.personal.assistant.data.dao.CategoryDao
import com.personal.assistant.data.dao.ChatDao
import com.personal.assistant.data.dao.MemoryDao
import com.personal.assistant.data.dao.PreferenceDao
import com.personal.assistant.data.dao.RecurrenceDao
import com.personal.assistant.data.dao.ReminderDao
import com.personal.assistant.data.dao.TaskDao
import com.personal.assistant.data.entity.ActivityLogEntity
import com.personal.assistant.data.entity.CategoryEntity
import com.personal.assistant.data.entity.ConversationEntity
import com.personal.assistant.data.entity.MemoryEntity
import com.personal.assistant.data.entity.MessageEntity
import com.personal.assistant.data.entity.PreferenceEntity
import com.personal.assistant.data.entity.RecurrenceEntity
import com.personal.assistant.data.entity.ReminderEntity
import com.personal.assistant.data.entity.TaskEntity
import com.personal.assistant.core.model.Category

@Database(
    entities = [
        CategoryEntity::class,
        RecurrenceEntity::class,
        TaskEntity::class,
        ActivityLogEntity::class,
        MemoryEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        ReminderEntity::class,
        PreferenceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun recurrenceDao(): RecurrenceDao
    abstract fun taskDao(): TaskDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun memoryDao(): MemoryDao
    abstract fun chatDao(): ChatDao
    abstract fun reminderDao(): ReminderDao
    abstract fun preferenceDao(): PreferenceDao

    companion object {
        private const val NAME = "assistant.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                // No fallbackToDestructiveMigration: silently wiping a year of someone's history on an
                // upgrade is never an acceptable outcome. Every future version ships a real migration.
                .addCallback(SeedCallback)
                .build()

        /** Seeds the built-in categories on first creation. */
        private object SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                Category.DEFAULTS.forEach { category ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO categories (name, colorArgb, builtIn) VALUES (?, ?, 1)",
                        arrayOf<Any>(category.name, category.colorArgb),
                    )
                }
            }
        }
    }
}
