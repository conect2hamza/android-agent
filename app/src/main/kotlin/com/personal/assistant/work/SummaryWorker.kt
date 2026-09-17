package com.personal.assistant.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.personal.assistant.AssistantApplication
import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.domain.ReportGenerator
import com.personal.assistant.core.domain.ResponseComposer
import com.personal.assistant.notify.Notifier

/**
 * Builds the end-of-day and end-of-week summaries and posts them, then schedules the next one.
 *
 * Self-rescheduling keeps this to a single job per summary rather than a periodic worker that would have
 * to wake every fifteen minutes to check whether it is time yet.
 */
class SummaryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? AssistantApplication)?.container ?: return Result.failure()
        val kind = inputData.getString(KEY_KIND) ?: KIND_DAILY
        val settings = container.settingsRepository.current()
        val now = container.now()
        val composer = ResponseComposer()

        val generator = ReportGenerator(container.taskRepository.categories())

        when (kind) {
            KIND_WEEKLY -> if (settings.weeklyReportEnabled) {
                val range = DateRange.week(now.toLocalDate().minusDays(1), settings.firstDayOfWeek)
                val report = generator.weekly(
                    range,
                    container.taskRepository.range(range),
                    container.taskRepository.logsInRange(range),
                    now,
                )
                container.notifier.postSummary(
                    Notifier.WEEKLY_REPORT_NOTIFICATION_ID,
                    "Weekly summary",
                    composer.weeklyReport(report),
                )
            }

            else -> if (settings.dailySummaryEnabled) {
                val today = now.toLocalDate()
                val report = generator.daily(
                    today,
                    container.taskRepository.range(DateRange.single(today)),
                    container.taskRepository.logsInRange(DateRange.single(today)),
                    now,
                )
                // Nothing planned means nothing worth interrupting for.
                if (report.totals.total > 0) {
                    container.notifier.postSummary(
                        Notifier.DAILY_SUMMARY_NOTIFICATION_ID,
                        "Daily summary",
                        composer.dailySummary(report, today),
                    )
                }
            }
        }

        SummaryScheduler.reschedule(applicationContext, settings, container.now())
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "kind"
        const val KIND_DAILY = "daily"
        const val KIND_WEEKLY = "weekly"
    }
}
