package com.personal.assistant.data.repository

import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.ai.TaskDraft
import com.personal.assistant.core.domain.Conflict
import com.personal.assistant.core.domain.SchedulingEngine
import com.personal.assistant.core.domain.SearchEngine
import com.personal.assistant.core.domain.TaskStateMachine
import com.personal.assistant.core.domain.TimeTracker
import com.personal.assistant.core.model.ActivityEvent
import com.personal.assistant.core.model.ActivityLog
import com.personal.assistant.core.model.Category
import com.personal.assistant.core.model.Recurrence
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.data.dao.ActivityLogDao
import com.personal.assistant.data.dao.CategoryDao
import com.personal.assistant.data.dao.RecurrenceDao
import com.personal.assistant.data.dao.TaskDao
import com.personal.assistant.data.mapper.Mappers.toEntity
import com.personal.assistant.data.mapper.Mappers.toEpochSecond
import com.personal.assistant.data.mapper.Mappers.toModel
import com.personal.assistant.notify.ReminderGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** What happened when a task was saved, including any overlap worth warning about. */
data class SaveOutcome(
    val task: Task,
    val conflicts: List<Conflict> = emptyList(),
    val occurrencesCreated: Int = 1,
)

class TaskRepository(
    private val taskDao: TaskDao,
    private val logDao: ActivityLogDao,
    private val categoryDao: CategoryDao,
    private val recurrenceDao: RecurrenceDao,
    private val reminders: ReminderGateway,
) {

    fun observeDay(date: LocalDate): Flow<List<Task>> =
        taskDao.observeForDay(date.toEpochDay()).map { rows -> rows.map { it.toModel() } }

    fun observeRange(range: DateRange): Flow<List<Task>> =
        taskDao.observeRange(range.start.toEpochDay(), range.end.toEpochDay())
            .map { rows -> rows.map { it.toModel() } }

    fun observeTask(id: Long): Flow<Task?> = taskDao.observeById(id).map { it?.toModel() }

    fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun categories(): List<Category> = categoryDao.all().map { it.toModel() }

    suspend fun task(id: Long): Task? = taskDao.byId(id)?.toModel()

    suspend fun range(range: DateRange): List<Task> =
        taskDao.range(range.start.toEpochDay(), range.end.toEpochDay()).map { it.toModel() }

    suspend fun recentTitles(limit: Int = 15): List<String> =
        taskDao.recent(limit).map { it.title }

    suspend fun logsFor(taskId: Long): List<ActivityLog> = logDao.forTask(taskId).map { it.toModel() }

    suspend fun logsInRange(range: DateRange): List<ActivityLog> = logDao.inRange(
        range.start.atStartOfDay().toEpochSecond(),
        range.end.plusDays(1).atStartOfDay().toEpochSecond() - 1,
    ).map { it.toModel() }

    // ------------------------------------------------------------------ writing

    /**
     * Saves a parsed draft.
     *
     * A draft carrying a repeat rule becomes a recurrence row plus concrete occurrences inside
     * [SERIES_HORIZON_DAYS]; the rest are generated later by [extendSeries]. Materialising a bounded
     * window rather than the whole future is what keeps a daily task from costing thousands of rows,
     * while still letting each occurrence be completed, rescheduled or skipped on its own.
     */
    suspend fun create(draft: TaskDraft, now: LocalDateTime, defaultReminderMinutes: Int): SaveOutcome {
        val date = draft.date ?: now.toLocalDate()
        val categoryId = draft.categoryName?.let { name -> categoryDao.byName(name)?.id }

        val base = Task(
            title = draft.title,
            description = draft.description,
            date = date,
            startTime = draft.startTime,
            endTime = draft.endTime ?: impliedEnd(draft),
            durationMinutes = draft.durationMinutes,
            priority = draft.priority,
            categoryId = categoryId,
            status = TaskStatus.PLANNED,
            reminderMinutes = when {
                draft.startTime == null -> null
                draft.reminderMinutes != null -> draft.reminderMinutes
                else -> defaultReminderMinutes
            },
            notes = draft.notes,
            createdAt = now,
            updatedAt = now,
        )

        val recurrence = draft.recurrence
        if (recurrence == null) {
            val id = taskDao.insert(base.toEntity())
            val saved = base.copy(id = id)
            log(id, ActivityEvent.CREATED, now)
            reminders.reschedule(saved)
            return SaveOutcome(saved, conflictsFor(saved), occurrencesCreated = 1)
        }

        val stored = recurrenceDao.upsert(
            Recurrence(
                frequency = recurrence.frequency,
                interval = recurrence.interval,
                daysOfWeek = recurrence.daysOfWeek,
                dayOfMonth = recurrence.dayOfMonth,
                startDate = date,
            ).toEntity(),
        )
        val rule = recurrenceDao.byId(stored)?.toModel()
            ?: return SaveOutcome(base.copy(id = taskDao.insert(base.toEntity())))

        val seriesId = (taskDao.maxSeriesId() ?: 0L) + 1
        val dates = SchedulingEngine.occurrences(rule, date..date.plusDays(SERIES_HORIZON_DAYS))
        val occurrences = dates.map { occurrenceDate ->
            base.copy(date = occurrenceDate, recurrenceId = stored, seriesId = seriesId).toEntity()
        }
        val ids = taskDao.insertAll(occurrences)
        val firstId = ids.firstOrNull() ?: taskDao.insert(base.toEntity())
        val first = taskDao.byId(firstId)?.toModel() ?: base.copy(id = firstId)

        // Only the near-term occurrences get alarms; the rest are armed as the horizon rolls forward.
        ids.zip(dates).forEach { (id, occurrenceDate) ->
            if (!occurrenceDate.isAfter(now.toLocalDate().plusDays(ALARM_HORIZON_DAYS))) {
                taskDao.byId(id)?.toModel()?.let { reminders.reschedule(it) }
            }
        }
        log(firstId, ActivityEvent.CREATED, now)
        return SaveOutcome(first, conflictsFor(first), occurrencesCreated = ids.size)
    }

    private fun impliedEnd(draft: TaskDraft): LocalTime? {
        val start = draft.startTime ?: return null
        val minutes = draft.durationMinutes ?: return null
        return start.plusMinutes(minutes.toLong())
    }

    suspend fun save(task: Task, now: LocalDateTime): SaveOutcome {
        val updated = task.copy(updatedAt = now)
        taskDao.update(updated.toEntity())
        reminders.reschedule(updated)
        return SaveOutcome(updated, conflictsFor(updated))
    }

    suspend fun changeStatus(taskId: Long, status: TaskStatus, now: LocalDateTime): Result<Task> {
        val task = task(taskId) ?: return Result.failure(NoSuchElementException("Task $taskId is gone"))
        return TaskStateMachine.apply(task, status, now).onSuccess { updated ->
            taskDao.update(updated.toEntity())
            log(taskId, status.toEvent(), now)
            if (updated.status.isTerminal) reminders.cancelFor(taskId) else reminders.reschedule(updated)
        }
    }

    suspend fun setProgress(taskId: Long, percent: Int, now: LocalDateTime): Result<Task> {
        val task = task(taskId) ?: return Result.failure(NoSuchElementException("Task $taskId is gone"))
        val clamped = percent.coerceIn(0, 100)
        val target = TaskStateMachine.statusForProgress(clamped)
        val withStatus = if (TaskStateMachine.canTransition(task.status, target)) target else task.status
        val updated = task.copy(
            progress = clamped,
            status = withStatus,
            completedAt = if (clamped >= 100) now else task.completedAt,
            updatedAt = now,
        )
        taskDao.update(updated.toEntity())
        log(taskId, if (clamped >= 100) ActivityEvent.COMPLETED else ActivityEvent.PROGRESS_UPDATED, now)
        if (updated.status.isTerminal) reminders.cancelFor(taskId) else reminders.reschedule(updated)
        return Result.success(updated)
    }

    suspend fun reschedule(
        taskId: Long,
        newDate: LocalDate?,
        newTime: LocalTime?,
        now: LocalDateTime,
    ): Result<Task> {
        val task = task(taskId) ?: return Result.failure(NoSuchElementException("Task $taskId is gone"))
        // Moving a task keeps its length: "move my 4-6 PM task to 6" should end at 8, not lose two hours.
        val length = task.plannedMinutes.takeIf { it > 0 }
        val start = newTime ?: task.startTime
        val updated = task.copy(
            date = newDate ?: task.date,
            startTime = start,
            endTime = when {
                start != null && length != null -> start.plusMinutes(length.toLong())
                else -> task.endTime
            },
            // A moved task is planned again, even if the clock had already written it off as missed.
            status = if (task.status == TaskStatus.MISSED || task.status == TaskStatus.POSTPONED) {
                TaskStatus.PLANNED
            } else {
                task.status
            },
            updatedAt = now,
        )
        taskDao.update(updated.toEntity())
        log(taskId, ActivityEvent.RESCHEDULED, now)
        reminders.reschedule(updated)
        return Result.success(updated)
    }

    suspend fun startTracking(taskId: Long, now: LocalDateTime): Result<Task> {
        val result = changeStatus(taskId, TaskStatus.STARTED, now)
        result.getOrNull()?.let { log(taskId, ActivityEvent.STARTED, now) }
        return result
    }

    suspend fun pauseTracking(taskId: Long, now: LocalDateTime) {
        log(taskId, ActivityEvent.PAUSED, now)
    }

    suspend fun resumeTracking(taskId: Long, now: LocalDateTime) {
        log(taskId, ActivityEvent.RESUMED, now)
    }

    suspend fun trackedMinutes(taskId: Long, now: LocalDateTime): Int =
        TimeTracker.tracked(logsFor(taskId), now).activeMinutes

    suspend fun delete(taskId: Long) {
        reminders.cancelFor(taskId)
        taskDao.deleteById(taskId)
    }

    /** Deletes the remaining future occurrences of a series, leaving history intact. */
    suspend fun deleteSeriesFrom(seriesId: Long, from: LocalDate) {
        taskDao.bySeries(seriesId)
            .filter { it.dateEpochDay >= from.toEpochDay() }
            .forEach { reminders.cancelFor(it.id) }
        taskDao.deleteSeriesFrom(seriesId, from.toEpochDay())
    }

    /**
     * Tops up recurring series so the calendar always has occurrences ahead of the user. Run from the
     * daily maintenance job rather than on every launch, because it writes rows.
     */
    suspend fun extendSeries(now: LocalDateTime) {
        val horizonEnd = now.toLocalDate().plusDays(SERIES_HORIZON_DAYS)
        for (entity in recurrenceDao.all()) {
            val rule = entity.toModel()
            val existing = taskDao.range(now.toLocalDate().toEpochDay(), horizonEnd.toEpochDay())
                .filter { it.recurrenceId == entity.id }
            val template = existing.maxByOrNull { it.dateEpochDay }
                ?: taskDao.all().firstOrNull { it.recurrenceId == entity.id }
                ?: continue
            val have = existing.map { it.dateEpochDay }.toSet()
            val wanted = SchedulingEngine.occurrences(rule, now.toLocalDate()..horizonEnd)
                .filterNot { it.toEpochDay() in have }
            if (wanted.isEmpty()) continue
            val model = template.toModel()
            taskDao.insertAll(
                wanted.map { date ->
                    model.copy(
                        id = 0,
                        date = date,
                        status = TaskStatus.PLANNED,
                        progress = 0,
                        completedAt = null,
                        createdAt = now,
                        updatedAt = now,
                    ).toEntity()
                },
            )
        }
    }

    /**
     * Writes the clock's verdict to the rows it applies to.
     *
     * Reports derive "missed" on the fly, but the status has to be persisted as well so the calendar,
     * the follow-up notifications and an export all agree.
     */
    suspend fun markOverdueAsMissed(now: LocalDateTime): List<Task> {
        val open = taskDao.withStatusUpTo(
            statuses = listOf(
                TaskStatus.PLANNED.name,
                TaskStatus.UPCOMING.name,
                TaskStatus.STARTED.name,
                TaskStatus.IN_PROGRESS.name,
            ),
            throughEpochDay = now.toLocalDate().toEpochDay(),
        )
        val missed = mutableListOf<Task>()
        for (entity in open) {
            val task = entity.toModel()
            if (TaskStateMachine.deriveForClock(task, now) != TaskStatus.MISSED) continue
            // A task the user started and left running is partial, not simply missed.
            val outcome = if (task.status.isActive && task.progress > 0) {
                TaskStatus.PARTIALLY_COMPLETED
            } else {
                TaskStatus.MISSED
            }
            TaskStateMachine.apply(task, outcome, now).getOrNull()?.let { updated ->
                taskDao.update(updated.toEntity())
                log(task.id, outcome.toEvent(), now)
                reminders.cancelFor(task.id)
                missed += updated
            }
        }
        return missed
    }

    // ----------------------------------------------------------------- querying

    suspend fun search(needle: String, limit: Int = 50): List<Task> =
        taskDao.search(needle.trim(), limit).map { it.toModel() }

    /**
     * Resolves a spoken reference to candidate tasks. A narrow database query first, then the scoring in
     * [SearchEngine], so the phone is not asked to rank the whole table.
     */
    suspend fun resolveReference(
        titleQuery: String?,
        date: LocalDate?,
        time: LocalTime?,
        now: LocalDateTime,
    ): List<Task> {
        val pool = when {
            !titleQuery.isNullOrBlank() -> search(titleQuery, limit = 60)
            date != null -> range(DateRange.single(date))
            else -> range(DateRange(now.toLocalDate().minusDays(7), now.toLocalDate().plusDays(30)))
        }
        val engine = SearchEngine(categories())
        val candidates = engine.resolveReference(titleQuery, date, time, pool)
        // Prefer things that are still open and closest to now; that is almost always what was meant.
        return candidates.sortedWith(
            compareBy(
                { it.status.isTerminal },
                { kotlin.math.abs(it.date.toEpochDay() - now.toLocalDate().toEpochDay()) },
            ),
        )
    }

    suspend fun conflictsFor(task: Task): List<Conflict> =
        SchedulingEngine.conflictsFor(task, range(DateRange.single(task.date)))

    private suspend fun log(taskId: Long?, event: ActivityEvent, at: LocalDateTime, notes: String? = null) {
        logDao.insert(
            ActivityLog(taskId = taskId, event = event, timestamp = at, notes = notes).toEntity(),
        )
    }

    private fun TaskStatus.toEvent(): ActivityEvent = when (this) {
        TaskStatus.COMPLETED -> ActivityEvent.COMPLETED
        TaskStatus.PARTIALLY_COMPLETED -> ActivityEvent.PARTIALLY_COMPLETED
        TaskStatus.CANCELLED -> ActivityEvent.CANCELLED
        TaskStatus.SKIPPED -> ActivityEvent.SKIPPED
        TaskStatus.MISSED -> ActivityEvent.MISSED
        TaskStatus.POSTPONED -> ActivityEvent.POSTPONED
        TaskStatus.STARTED -> ActivityEvent.STARTED
        TaskStatus.IN_PROGRESS -> ActivityEvent.PROGRESS_UPDATED
        else -> ActivityEvent.NOTE_ADDED
    }

    companion object {
        /** How far ahead recurring occurrences are materialised. */
        const val SERIES_HORIZON_DAYS = 60L

        /** How far ahead exact alarms are armed; the rest follow as the horizon moves. */
        const val ALARM_HORIZON_DAYS = 7L
    }
}
