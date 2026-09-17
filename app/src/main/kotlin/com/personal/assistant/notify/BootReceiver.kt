package com.personal.assistant.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personal.assistant.AssistantApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Rebuilds the schedule after anything that invalidates pending alarms: a reboot, an app update, or the
 * user changing the clock or time zone.
 *
 * Without this, every reminder set before a restart would simply never arrive -- the failure mode most
 * likely to make someone stop trusting the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> return
        }

        val container = (context.applicationContext as? AssistantApplication)?.container ?: return
        val pendingResult = goAsync()

        container.applicationScope.launch(Dispatchers.IO) {
            try {
                container.alarmScheduler.rescheduleAll()
                container.alarmScheduler.rescheduleSummaries()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
