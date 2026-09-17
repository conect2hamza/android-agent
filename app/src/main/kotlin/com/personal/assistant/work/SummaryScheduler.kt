package com.personal.assistant.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.personal.assistant.data.repository.AppSettings
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Schedules the background jobs that are not time-critical.
 *
 * These use WorkManager rather than alarms on purpose. A summary that arrives at 21:04 instead of 21:00
 * costs the user nothing, and letting the system batch it with other deferred work is a real saving
 * against the app's battery budget. WorkManager also survives reboots on its own, so none of this needs
 * rebuilding in the boot receiver.
 */
object SummaryScheduler {

    private const val DAILY_SUMMARY_WORK = "daily_summary"
    private const val WEEKLY_REPORT_WORK = "weekly_report"
    private const val MAINTENANCE_WORK = "nightly_maintenance"

    fun reschedule(context: Context, settings: AppSettings, now: LocalDateTime) {
        val manager = WorkManager.getInstance(context)

        if (settings.dailySummaryEnabled) {
            val at = nextOccurrence(now, settings.dailySummaryTime)
            manager.enqueueUniqueWork(
                DAILY_SUMMARY_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SummaryWorker>()
                    .setInitialDelay(Duration.between(now, at).toMinutes(), TimeUnit.MINUTES)
                    .setInputData(workDataOf(SummaryWorker.KEY_KIND to SummaryWorker.KIND_DAILY))
                    .build(),
            )
        } else {
            manager.cancelUniqueWork(DAILY_SUMMARY_WORK)
        }

        if (settings.weeklyReportEnabled) {
            // The report lands on the last day of the user's week, once that week is over.
            val reportDay = settings.firstDayOfWeek.minus(1)
            val target = now.with(TemporalAdjusters.nextOrSame(reportDay))
                .withHour(settings.dailySummaryTime.hour)
                .withMinute(settings.dailySummaryTime.minute)
            val at = if (target.isAfter(now)) target else target.plusWeeks(1)
            manager.enqueueUniqueWork(
                WEEKLY_REPORT_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SummaryWorker>()
                    .setInitialDelay(Duration.between(now, at).toMinutes(), TimeUnit.MINUTES)
                    .setInputData(workDataOf(SummaryWorker.KEY_KIND to SummaryWorker.KIND_WEEKLY))
                    .build(),
            )
        } else {
            manager.cancelUniqueWork(WEEKLY_REPORT_WORK)
        }

        // Runs whether or not summaries are on: missed tasks still need recording and recurring series
        // still need topping up.
        manager.enqueueUniquePeriodicWork(
            MAINTENANCE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInitialDelay(nightlyDelayMinutes(now), TimeUnit.MINUTES)
                .build(),
        )
    }

    /** Next time today's clock reaches [time], or the same time tomorrow if it already has. */
    fun nextOccurrence(now: LocalDateTime, time: java.time.LocalTime): LocalDateTime {
        val candidate = now.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
        return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
    }

    /** Aims the maintenance pass at 03:00, when the phone is most likely idle and charging. */
    private fun nightlyDelayMinutes(now: LocalDateTime): Long =
        Duration.between(now, nextOccurrence(now, java.time.LocalTime.of(3, 0))).toMinutes()
}
