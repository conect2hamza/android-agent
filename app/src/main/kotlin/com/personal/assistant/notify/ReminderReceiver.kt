package com.personal.assistant.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personal.assistant.AssistantApplication
import com.personal.assistant.core.model.ReminderKind
import com.personal.assistant.core.model.TaskStatus
import com.personal.assistant.core.domain.TaskStateMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Posts a reminder when its alarm fires.
 *
 * The work is short but it does touch the database, so it runs on a scope that outlives the receiver
 * with a `goAsync` lease rather than blocking the main thread.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { name -> ReminderKind.entries.firstOrNull { it.name == name } }
            ?: return
        if (taskId <= 0L) return

        val container = (context.applicationContext as? AssistantApplication)?.container ?: return
        val pendingResult = goAsync()

        container.applicationScope.launch(Dispatchers.IO) {
            try {
                val task = container.taskRepository.task(taskId) ?: return@launch
                // The alarm may be stale: the task could have been completed or cancelled since.
                if (task.status.isTerminal) return@launch

                val now = container.now()
                when (kind) {
                    ReminderKind.PROGRESS_CHECK ->
                        // Only ask about progress on something the user actually started.
                        if (!task.status.isActive) return@launch

                    ReminderKind.COMPLETION_CHECK ->
                        if (task.status == TaskStatus.COMPLETED) return@launch

                    else -> Unit
                }

                // Reaching the reminder window is itself a status change worth persisting.
                if (kind == ReminderKind.LEAD_UP || kind == ReminderKind.START) {
                    val derived = TaskStateMachine.deriveForClock(task, now)
                    if (derived != task.status) {
                        container.taskRepository.changeStatus(taskId, derived, now)
                    }
                }

                container.notifier.post(task, kind, now.toLocalDate())
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "com.personal.assistant.extra.ALARM_TASK_ID"
        const val EXTRA_KIND = "com.personal.assistant.extra.ALARM_KIND"
        const val EXTRA_REQUEST_CODE = "com.personal.assistant.extra.ALARM_REQUEST_CODE"
    }
}
