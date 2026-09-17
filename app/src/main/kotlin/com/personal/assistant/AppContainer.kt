package com.personal.assistant

import android.content.Context
import com.personal.assistant.ai.LocalLlmProvider
import com.personal.assistant.ai.ModelStore
import com.personal.assistant.ai.NoLlmRuntime
import com.personal.assistant.assistant.AssistantService
import com.personal.assistant.core.ai.AiProvider
import com.personal.assistant.core.ai.AssistantInterpreter
import com.personal.assistant.core.ai.NoAiProvider
import com.personal.assistant.core.ai.RuleBasedParser
import com.personal.assistant.core.domain.ResponseComposer
import com.personal.assistant.data.db.AppDatabase
import com.personal.assistant.data.repository.BackupRepository
import com.personal.assistant.data.repository.ChatRepository
import com.personal.assistant.data.repository.MemoryRepository
import com.personal.assistant.data.repository.SettingsRepository
import com.personal.assistant.data.repository.TaskRepository
import com.personal.assistant.notify.AlarmScheduler
import com.personal.assistant.notify.Notifier
import com.personal.assistant.security.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import java.time.LocalDateTime

/**
 * Manual dependency wiring.
 *
 * No DI framework: the graph is a couple of dozen objects that never change shape at runtime, and an
 * annotation processor would add build time and APK weight to an app whose stated goals are staying
 * small and starting fast. Everything is lazy, so opening the app does not construct the model layer.
 */
class AppContainer(private val context: Context) {

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Injected everywhere instead of calling `LocalDateTime.now()` inline, so time is testable. */
    fun now(): LocalDateTime = LocalDateTime.now()

    private val database: AppDatabase by lazy { AppDatabase.build(context) }

    val crypto: CryptoManager by lazy { CryptoManager() }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(database.preferenceDao()) }

    val notifier: Notifier by lazy { Notifier(context, ResponseComposer()) }

    val modelStore: ModelStore by lazy { ModelStore(context) }

    val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler(
            context = context,
            taskDao = database.taskDao(),
            reminderDao = database.reminderDao(),
            settings = settingsRepository,
            clock = ::now,
        )
    }

    val taskRepository: TaskRepository by lazy {
        TaskRepository(
            taskDao = database.taskDao(),
            logDao = database.activityLogDao(),
            categoryDao = database.categoryDao(),
            recurrenceDao = database.recurrenceDao(),
            reminders = alarmScheduler,
        )
    }

    val memoryRepository: MemoryRepository by lazy { MemoryRepository(database.memoryDao(), crypto) }

    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao(), crypto) }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(
            taskDao = database.taskDao(),
            logDao = database.activityLogDao(),
            categoryDao = database.categoryDao(),
            recurrenceDao = database.recurrenceDao(),
            memoryDao = database.memoryDao(),
            chatDao = database.chatDao(),
            reminderDao = database.reminderDao(),
            preferenceDao = database.preferenceDao(),
            crypto = crypto,
            reminders = alarmScheduler,
        )
    }

    /** The currently loaded model provider, if any. Held so it can be released on background. */
    @Volatile
    private var activeProvider: AiProvider = NoAiProvider

    /**
     * Rebuilt per turn from the current settings, so switching model or turning the model off in
     * settings takes effect on the very next message without restarting anything.
     */
    suspend fun interpreter(): AssistantInterpreter {
        val preferences = settingsRepository.current()
        val provider = when {
            !preferences.aiEnabled -> NoAiProvider
            preferences.aiModelId == "none" -> NoAiProvider
            modelStore.find(preferences.aiModelId) == null -> NoAiProvider
            else -> LocalLlmProvider(
                // No inference engine ships in 1.0; see LlmRuntime for why. Swapping this one
                // constructor argument is the whole of adding one.
                runtime = NoLlmRuntime,
                modelStore = modelStore,
                modelId = preferences.aiModelId,
            )
        }
        activeProvider = provider

        return AssistantInterpreter(
            rules = RuleBasedParser(
                categoryNames = taskRepository.categories().map { it.name },
                firstDayOfWeek = preferences.firstDayOfWeek,
            ),
            provider = provider,
        )
    }

    val assistantService: AssistantService by lazy {
        AssistantService(
            interpreterFactory = ::interpreter,
            tasks = taskRepository,
            memories = memoryRepository,
            chat = chatRepository,
            settings = settingsRepository,
            composer = ResponseComposer(),
            now = ::now,
        )
    }

    /** Called when the app goes to the background: the model must not stay resident. */
    suspend fun releaseModel() {
        runCatching { activeProvider.release() }
    }
}
