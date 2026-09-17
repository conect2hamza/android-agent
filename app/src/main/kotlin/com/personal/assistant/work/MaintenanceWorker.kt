package com.personal.assistant.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personal.assistant.AssistantApplication
import com.personal.assistant.core.model.ReminderKind

/**
 * The nightly pass: record what was missed, follow up on it, extend recurring series, and re-arm the
 * alarms for the days that have just come inside the alarm horizon.
 *
 * Doing this once a day, while the phone is idle, is what lets the rest of the app avoid polling.
 */
class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? AssistantApplication)?.container ?: return Result.failure()
        val now = container.now()

        return runCatching {
            val missed = container.taskRepository.markOverdueAsMissed(now)
            container.taskRepository.extendSeries(now)
            container.alarmScheduler.rescheduleAll()

            // One follow-up per missed task, capped: a morning of twelve notifications teaches the user
            // to swipe them all away without reading.
            missed.take(MAX_MISSED_FOLLOW_UPS).forEach { task ->
                container.notifier.post(task, ReminderKind.MISSED_FOLLOW_UP, now.toLocalDate())
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val MAX_MISSED_FOLLOW_UPS = 3
    }
}
