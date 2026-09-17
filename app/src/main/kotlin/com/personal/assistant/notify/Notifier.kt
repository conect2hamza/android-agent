package com.personal.assistant.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.personal.assistant.MainActivity
import com.personal.assistant.R
import com.personal.assistant.core.domain.ResponseComposer
import com.personal.assistant.core.model.ReminderKind
import com.personal.assistant.core.model.Task
import java.time.LocalDate

/**
 * Builds and posts the notifications.
 *
 * Every follow-up the specification asks for is answerable from the shade: starting a task, recording
 * progress, confirming completion, moving a missed task. Requiring the app to be opened for a one-tap
 * answer is the quickest way to make someone stop answering at all.
 */
class Notifier(
    private val context: Context,
    private val composer: ResponseComposer = ResponseComposer(),
) {

    private val manager = NotificationManagerCompat.from(context)

    /** False when the user has denied or switched off notifications; surfaced in the UI as a warning. */
    fun areNotificationsEnabled(): Boolean = manager.areNotificationsEnabled()

    fun post(task: Task, kind: ReminderKind, today: LocalDate) {
        val builder = when (kind) {
            ReminderKind.LEAD_UP -> leadUp(task)
            ReminderKind.START -> start(task)
            ReminderKind.PROGRESS_CHECK -> progressCheck(task)
            ReminderKind.COMPLETION_CHECK -> completionCheck(task)
            ReminderKind.MISSED_FOLLOW_UP -> missed(task, today)
            ReminderKind.DAILY_SUMMARY, ReminderKind.WEEKLY_REPORT -> return
        }
        notify(notificationId(task.id, kind), builder.build())
    }

    fun postSummary(id: Int, title: String, body: String) {
        val builder = base(NotificationChannels.SUMMARIES)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentText(body.lineSequence().firstOrNull() ?: title)
        notify(id, builder.build())
    }

    fun cancel(taskId: Long, kind: ReminderKind) = manager.cancel(notificationId(taskId, kind))

    fun cancelAll(taskId: Long) = ReminderKind.entries.forEach { cancel(taskId, it) }

    // ------------------------------------------------------------------ builders

    private fun leadUp(task: Task) = base(NotificationChannels.REMINDERS)
        .setContentTitle(task.title)
        .setContentText(
            task.startTime?.let { "Scheduled at ${composer.time(it)}." } ?: "Scheduled today.",
        )
        .addAction(0, context.getString(R.string.action_open), openAppIntent(task.id))

    /**
     * Android shows at most three actions, so the fourth option from the specification -- Skip -- is
     * reached by opening the task rather than crowding out Snooze. Skipping is also offered directly on
     * the completion follow-up, where it is the more common answer.
     */
    private fun start(task: Task) = base(NotificationChannels.REMINDERS)
        .setContentTitle(task.title)
        .setContentText("Starting now. Are you starting it?")
        .addAction(0, context.getString(R.string.action_start), action(NotificationActions.START, task.id))
        .addAction(0, context.getString(R.string.action_snooze), action(NotificationActions.SNOOZE, task.id))
        .addAction(0, context.getString(R.string.action_reschedule), openAppIntent(task.id))

    private fun progressCheck(task: Task) = base(NotificationChannels.FOLLOW_UPS)
        .setContentTitle(task.title)
        .setContentText("How much progress have you made?")
        .addAction(0, "25%", action(NotificationActions.SET_PROGRESS, task.id, value = 25))
        .addAction(0, "50%", action(NotificationActions.SET_PROGRESS, task.id, value = 50))
        .addAction(0, "75%", action(NotificationActions.SET_PROGRESS, task.id, value = 75))

    private fun completionCheck(task: Task) = base(NotificationChannels.FOLLOW_UPS)
        .setContentTitle(task.title)
        .setStyle(NotificationCompat.BigTextStyle().bigText(composer.completionFollowUp(task)))
        .setContentText("Did you complete it?")
        .addAction(0, context.getString(R.string.action_complete), action(NotificationActions.COMPLETE, task.id))
        .addAction(0, context.getString(R.string.action_partial), action(NotificationActions.PARTIAL, task.id))
        .addAction(0, context.getString(R.string.action_not_done), action(NotificationActions.NOT_DONE, task.id))

    private fun missed(task: Task, today: LocalDate) = base(NotificationChannels.FOLLOW_UPS)
        .setContentTitle(task.title)
        .setContentText(composer.missedFollowUp(task, today))
        .addAction(0, "Move to tomorrow", action(NotificationActions.MOVE_TO_TOMORROW, task.id))
        .addAction(0, context.getString(R.string.action_skip), action(NotificationActions.SKIP, task.id))

    private fun base(channelId: String) = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        // No task title on the lock screen beyond what the user's own lock-screen setting allows.
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setContentIntent(openAppIntent(null))

    private fun notify(id: Int, notification: Notification) {
        // A denied POST_NOTIFICATIONS permission throws on some OEM builds rather than no-oping.
        runCatching { manager.notify(id, notification) }
    }

    private fun openAppIntent(taskId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            taskId?.let { putExtra(NotificationActions.EXTRA_TASK_ID, it) }
        }
        return PendingIntent.getActivity(
            context,
            (taskId ?: 0L).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(name: String, taskId: Long, value: Int = -1): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            // A distinct data-free action string per button keeps PendingIntents from being merged.
            this.action = "com.personal.assistant.action.$name.$taskId.$value"
            putExtra(NotificationActions.EXTRA_ACTION, name)
            putExtra(NotificationActions.EXTRA_TASK_ID, taskId)
            putExtra(NotificationActions.EXTRA_VALUE, value)
        }
        return PendingIntent.getBroadcast(
            context,
            (taskId.toInt() * 31 + name.hashCode() + value),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(taskId: Long, kind: ReminderKind): Int =
        ((taskId % 1_000_000L).toInt() * ReminderKind.entries.size) + kind.ordinal

    companion object {
        const val DAILY_SUMMARY_NOTIFICATION_ID = 900_000_001
        const val WEEKLY_REPORT_NOTIFICATION_ID = 900_000_002
    }
}
