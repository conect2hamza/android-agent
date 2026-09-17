package com.personal.assistant.data.repository

import com.personal.assistant.core.model.Task
import com.personal.assistant.data.dao.PreferenceDao
import com.personal.assistant.data.entity.PreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalTime

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

enum class AssistantLanguage { AUTO, ENGLISH, URDU }

enum class ResponseLength { BRIEF, NORMAL, DETAILED }

/**
 * Every user-facing setting, with defaults chosen to match the specification's stated behaviour:
 * reminders 30 minutes ahead, memory on, summaries on, and the language model *off* until the user
 * installs one.
 */
data class AppSettings(
    val language: AssistantLanguage = AssistantLanguage.AUTO,
    val use24HourClock: Boolean = false,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val defaultReminderMinutes: Int = Task.DEFAULT_REMINDER_MINUTES,
    val dailySummaryEnabled: Boolean = true,
    val dailySummaryTime: LocalTime = LocalTime.of(21, 0),
    val weeklyReportEnabled: Boolean = true,
    val progressRemindersEnabled: Boolean = true,
    /** The assistant works without a model; this only controls whether an installed one is used. */
    val aiEnabled: Boolean = true,
    val aiModelId: String = "none",
    val responseLength: ResponseLength = ResponseLength.NORMAL,
    val memoryEnabled: Boolean = true,
    val encryptionEnabled: Boolean = true,
    val appLockEnabled: Boolean = false,
    val conflictWarningsEnabled: Boolean = true,
)

class SettingsRepository(private val dao: PreferenceDao) {

    fun observe(): Flow<AppSettings> = dao.observeAll().map { rows ->
        val values = rows.associateBy({ it.key }, { it.value })
        AppSettings(
            language = values.enum(KEY_LANGUAGE, AssistantLanguage.entries, AssistantLanguage.AUTO),
            use24HourClock = values.bool(KEY_24H, false),
            firstDayOfWeek = values.enum(KEY_FIRST_DAY, DayOfWeek.entries, DayOfWeek.MONDAY),
            theme = values.enum(KEY_THEME, ThemeChoice.entries, ThemeChoice.SYSTEM),
            defaultReminderMinutes = values.int(KEY_DEFAULT_REMINDER, Task.DEFAULT_REMINDER_MINUTES)
                .coerceIn(0, 24 * 60),
            dailySummaryEnabled = values.bool(KEY_DAILY_SUMMARY, true),
            dailySummaryTime = values.time(KEY_DAILY_SUMMARY_TIME, LocalTime.of(21, 0)),
            weeklyReportEnabled = values.bool(KEY_WEEKLY_REPORT, true),
            progressRemindersEnabled = values.bool(KEY_PROGRESS_REMINDERS, true),
            aiEnabled = values.bool(KEY_AI_ENABLED, true),
            aiModelId = values[KEY_AI_MODEL] ?: "none",
            responseLength = values.enum(KEY_RESPONSE_LENGTH, ResponseLength.entries, ResponseLength.NORMAL),
            memoryEnabled = values.bool(KEY_MEMORY, true),
            encryptionEnabled = values.bool(KEY_ENCRYPTION, true),
            appLockEnabled = values.bool(KEY_APP_LOCK, false),
            conflictWarningsEnabled = values.bool(KEY_CONFLICT_WARNINGS, true),
        )
    }

    suspend fun current(): AppSettings {
        val values = dao.all().associateBy({ it.key }, { it.value })
        return AppSettings(
            language = values.enum(KEY_LANGUAGE, AssistantLanguage.entries, AssistantLanguage.AUTO),
            use24HourClock = values.bool(KEY_24H, false),
            firstDayOfWeek = values.enum(KEY_FIRST_DAY, DayOfWeek.entries, DayOfWeek.MONDAY),
            theme = values.enum(KEY_THEME, ThemeChoice.entries, ThemeChoice.SYSTEM),
            defaultReminderMinutes = values.int(KEY_DEFAULT_REMINDER, Task.DEFAULT_REMINDER_MINUTES)
                .coerceIn(0, 24 * 60),
            dailySummaryEnabled = values.bool(KEY_DAILY_SUMMARY, true),
            dailySummaryTime = values.time(KEY_DAILY_SUMMARY_TIME, LocalTime.of(21, 0)),
            weeklyReportEnabled = values.bool(KEY_WEEKLY_REPORT, true),
            progressRemindersEnabled = values.bool(KEY_PROGRESS_REMINDERS, true),
            aiEnabled = values.bool(KEY_AI_ENABLED, true),
            aiModelId = values[KEY_AI_MODEL] ?: "none",
            responseLength = values.enum(KEY_RESPONSE_LENGTH, ResponseLength.entries, ResponseLength.NORMAL),
            memoryEnabled = values.bool(KEY_MEMORY, true),
            encryptionEnabled = values.bool(KEY_ENCRYPTION, true),
            appLockEnabled = values.bool(KEY_APP_LOCK, false),
            conflictWarningsEnabled = values.bool(KEY_CONFLICT_WARNINGS, true),
        )
    }

    suspend fun put(key: String, value: String) = dao.put(PreferenceEntity(key, value))

    suspend fun putAll(values: Map<String, String>) =
        dao.putAll(values.map { (key, value) -> PreferenceEntity(key, value) })

    suspend fun all(): Map<String, String> = dao.all().associateBy({ it.key }, { it.value })

    suspend fun setLanguage(value: AssistantLanguage) = put(KEY_LANGUAGE, value.name)
    suspend fun set24HourClock(value: Boolean) = put(KEY_24H, value.toString())
    suspend fun setFirstDayOfWeek(value: DayOfWeek) = put(KEY_FIRST_DAY, value.name)
    suspend fun setTheme(value: ThemeChoice) = put(KEY_THEME, value.name)
    suspend fun setDefaultReminderMinutes(value: Int) = put(KEY_DEFAULT_REMINDER, value.toString())
    suspend fun setDailySummaryEnabled(value: Boolean) = put(KEY_DAILY_SUMMARY, value.toString())
    suspend fun setDailySummaryTime(value: LocalTime) = put(KEY_DAILY_SUMMARY_TIME, value.toString())
    suspend fun setWeeklyReportEnabled(value: Boolean) = put(KEY_WEEKLY_REPORT, value.toString())
    suspend fun setProgressRemindersEnabled(value: Boolean) = put(KEY_PROGRESS_REMINDERS, value.toString())
    suspend fun setAiEnabled(value: Boolean) = put(KEY_AI_ENABLED, value.toString())
    suspend fun setAiModelId(value: String) = put(KEY_AI_MODEL, value)
    suspend fun setResponseLength(value: ResponseLength) = put(KEY_RESPONSE_LENGTH, value.name)
    suspend fun setMemoryEnabled(value: Boolean) = put(KEY_MEMORY, value.toString())
    suspend fun setEncryptionEnabled(value: Boolean) = put(KEY_ENCRYPTION, value.toString())
    suspend fun setAppLockEnabled(value: Boolean) = put(KEY_APP_LOCK, value.toString())
    suspend fun setConflictWarningsEnabled(value: Boolean) = put(KEY_CONFLICT_WARNINGS, value.toString())

    // ------------------------------------------------------------------ parsing

    private fun Map<String, String>.bool(key: String, default: Boolean): Boolean =
        this[key]?.toBooleanStrictOrNull() ?: default

    private fun Map<String, String>.int(key: String, default: Int): Int =
        this[key]?.toIntOrNull() ?: default

    private fun Map<String, String>.time(key: String, default: LocalTime): LocalTime =
        this[key]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: default

    private fun <T : Enum<T>> Map<String, String>.enum(key: String, values: List<T>, default: T): T =
        this[key]?.let { stored -> values.firstOrNull { it.name == stored } } ?: default

    companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_24H = "use_24_hour_clock"
        const val KEY_FIRST_DAY = "first_day_of_week"
        const val KEY_THEME = "theme"
        const val KEY_DEFAULT_REMINDER = "default_reminder_minutes"
        const val KEY_DAILY_SUMMARY = "daily_summary_enabled"
        const val KEY_DAILY_SUMMARY_TIME = "daily_summary_time"
        const val KEY_WEEKLY_REPORT = "weekly_report_enabled"
        const val KEY_PROGRESS_REMINDERS = "progress_reminders_enabled"
        const val KEY_AI_ENABLED = "ai_enabled"
        const val KEY_AI_MODEL = "ai_model_id"
        const val KEY_RESPONSE_LENGTH = "response_length"
        const val KEY_MEMORY = "memory_enabled"
        const val KEY_ENCRYPTION = "encryption_enabled"
        const val KEY_APP_LOCK = "app_lock_enabled"
        const val KEY_CONFLICT_WARNINGS = "conflict_warnings_enabled"
    }
}
