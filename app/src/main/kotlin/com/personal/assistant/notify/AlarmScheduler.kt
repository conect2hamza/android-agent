package com.personal.assistant.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import com.personal.assistant.core.domain.ReminderPlanner
import com.personal.assistant.core.model.Reminder
import com.personal.assistant.core.model.ReminderKind
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.data.dao.ReminderDao
import com.personal.assistant.data.dao.TaskDao
import com.personal.assistant.data.mapper.Mappers.toEntity
import com.personal.assistant.data.mapper.Mappers.toEpochSecond
import com.personal.assistant.data.mapper.Mappers.toModel
import com.personal.assistant.data.repository.SettingsRepository
import com.personal.assistant.data.repository.TaskRepository
import com.personal.assistant.work.SummaryScheduler
import java.time.LocalDateTime

/**
 * Turns the reminder plan into Android alarms.
 *
 * Exact alarms are used for the reminder and start notifications, because a planner that fires "some
 * time in the next hour" is not a planner. Everything else -- the daily summary, the weekly report, the
 * overnight maintenance pass -- goes through WorkManager instead, which batches with other system work
 * and costs far less battery than an exact alarm would.
 *
 * On Android 12 and later the exact-alarm permission can be revoked at any time. When that happens the
 * app degrades to an inexact window rather than silently scheduling nothing, and [canScheduleExact]
 * lets the UI tell the user that their reminders will drift.
 */
class AlarmScheduler(
    private val context: Context,
    private val taskDao: TaskDao,
    private val reminderDao: ReminderDao,
    private val settings: SettingsRepository,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) : ReminderGateway {

    private val alarmManager: AlarmManager? = context.getSystemService()

    fun canScheduleExact(): Boolean {
        val manager = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true
    }

    override suspend fun reschedule(task: Task) {
        cancelFor(task.id)
        if (task.status.isTerminal) return

        val preferences = settings.current()
        val planned = ReminderPlanner.plan(
            task = task,
            now = clock(),
            progressChecksEnabled = preferences.progressRemindersEnabled,
        )
        if (planned.isEmpty()) return

        reminderDao.insertAll(planned.map { it.toEntity() })
        planned.forEach { schedule(it) }
    }

    override suspend fun cancelFor(taskId: Long) {
        reminderDao.forTask(taskId).forEach { entity ->
            cancel(entity.requestCode)
        }
        reminderDao.deleteForTask(taskId)
    }

    /**
     * Rebuilds every alarm. Called after a reboot, a time change, an import, or a permission change,
     * because none of those preserve pending alarms.
     */
    override suspend fun rescheduleAll() {
        val now = clock()
        val horizonEnd = now.toLocalDate().plusDays(TaskRepository.ALARM_HORIZON_DAYS)
        reminderDao.pending().forEach { cancel(it.requestCode) }
        reminderDao.deleteAll()

        val upcoming = taskDao.range(now.toLocalDate().toEpochDay(), horizonEnd.toEpochDay())
            .map { it.toModel() }
            .filter { it.status != TaskStatus.CANCELLED && !it.status.isTerminal }

        val preferences = settings.current()
        val reminders = upcoming.flatMap { task ->
            ReminderPlanner.plan(task, now, preferences.progressRemindersEnabled)
        }
        if (reminders.isEmpty()) return
        reminderDao.insertAll(reminders.map { it.toEntity() })
        reminders.forEach { schedule(it) }
    }

    override suspend fun rescheduleSummaries() {
        SummaryScheduler.reschedule(context, settings.current(), clock())
    }

    /** Pushes a single follow-up back, for the Snooze button. */
    suspend fun snooze(taskId: Long, kind: ReminderKind, minutes: Long) {
        val task = taskDao.byId(taskId)?.toModel() ?: return
        val at = clock().plusMinutes(minutes)
        val reminder = Reminder(
            taskId = task.id,
            kind = kind,
            triggerAt = at,
            requestCode = ReminderPlanner.requestCode(task.id, kind),
        )
        reminderDao.insertAll(listOf(reminder.toEntity()))
        schedule(reminder)
    }

    // ------------------------------------------------------------------ internals

    private fun schedule(reminder: Reminder) {
        val manager = alarmManager ?: return
        val triggerAtMillis = reminder.triggerAt.toEpochSecond() * 1000L
        val intent = pendingIntent(reminder)
        runCatching {
            if (canScheduleExact()) {
                // ...AndAllowWhileIdle is what gets through Doze; a plain setExact would not.
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, intent)
            } else {
                manager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    INEXACT_WINDOW_MILLIS,
                    intent,
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not schedule ${reminder.kind} for task ${reminder.taskId}", error)
        }
    }

    private fun cancel(requestCode: Int) {
        val manager = alarmManager ?: return
        // The action must match the one used when scheduling: PendingIntent lookup compares intents
        // with filterEquals, which takes the action into account. Without it the existing alarm is
        // never found and cancelling silently does nothing.
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = alarmAction(requestCode)
        }
        PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { existing ->
            manager.cancel(existing)
            existing.cancel()
        }
    }

    private fun pendingIntent(reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = alarmAction(reminder.requestCode)
            putExtra(ReminderReceiver.EXTRA_TASK_ID, reminder.taskId ?: -1L)
            putExtra(ReminderReceiver.EXTRA_KIND, reminder.kind.name)
            putExtra(ReminderReceiver.EXTRA_REQUEST_CODE, reminder.requestCode)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmAction(requestCode: Int): String = "com.personal.assistant.alarm.$requestCode"

    companion object {
        private const val TAG = "AlarmScheduler"

        /** Width of the fallback window when exact alarms are unavailable. */
        private const val INEXACT_WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
