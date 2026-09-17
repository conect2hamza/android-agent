package com.personal.assistant.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personal.assistant.AssistantApplication
import com.personal.assistant.core.model.ReminderKind
import com.personal.assistant.core.model.Sender
import com.personal.assistant.core.model.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the notification buttons.
 *
 * Every outcome is also written into the chat as a system message, so the conversation stays a complete
 * record of what happened even when the user answered from the shade and never opened the app.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(NotificationActions.EXTRA_ACTION) ?: return
        val taskId = intent.getLongExtra(NotificationActions.EXTRA_TASK_ID, -1L)
        val value = intent.getIntExtra(NotificationActions.EXTRA_VALUE, -1)
        if (taskId <= 0L) return

        val container = (context.applicationContext as? AssistantApplication)?.container ?: return
        val pendingResult = goAsync()

        container.applicationScope.launch(Dispatchers.IO) {
            try {
                val tasks = container.taskRepository
                val now = container.now()
                val task = tasks.task(taskId) ?: return@launch

                val note = when (action) {
                    NotificationActions.START -> {
                        tasks.startTracking(taskId, now)
                        "Started \"${task.title}\"."
                    }

                    NotificationActions.SNOOZE -> {
                        container.alarmScheduler.snooze(
                            taskId,
                            ReminderKind.START,
                            NotificationActions.SNOOZE_MINUTES,
                        )
                        "Snoozed \"${task.title}\" for ${NotificationActions.SNOOZE_MINUTES} minutes."
                    }

                    NotificationActions.SKIP -> {
                        tasks.changeStatus(taskId, TaskStatus.SKIPPED, now)
                        "Skipped \"${task.title}\"."
                    }

                    NotificationActions.COMPLETE -> {
                        tasks.setProgress(taskId, 100, now)
                        "Completed \"${task.title}\"."
                    }

                    NotificationActions.PARTIAL -> {
                        tasks.changeStatus(taskId, TaskStatus.PARTIALLY_COMPLETED, now)
                        "Recorded \"${task.title}\" as partly done."
                    }

                    NotificationActions.NOT_DONE -> {
                        tasks.changeStatus(taskId, TaskStatus.MISSED, now)
                        "Recorded \"${task.title}\" as not done."
                    }

                    NotificationActions.SET_PROGRESS -> {
                        if (value !in 0..100) return@launch
                        tasks.setProgress(taskId, value, now)
                        "\"${task.title}\" is at $value%."
                    }

                    NotificationActions.MOVE_TO_TOMORROW -> {
                        tasks.reschedule(taskId, task.date.plusDays(1), task.startTime, now)
                        "Moved \"${task.title}\" to tomorrow."
                    }

                    else -> return@launch
                }

                container.notifier.cancelAll(taskId)

                val conversation = container.chatRepository.activeConversation(now)
                container.chatRepository.append(
                    conversationId = conversation.id,
                    sender = Sender.SYSTEM,
                    content = note,
                    at = now,
                    relatedTaskId = taskId,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
