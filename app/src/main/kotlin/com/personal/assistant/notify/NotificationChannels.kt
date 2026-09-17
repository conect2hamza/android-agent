package com.personal.assistant.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.personal.assistant.R

/**
 * Three channels, split by how interruptive each kind of message is, so the user can silence the daily
 * summary without losing the reminder that a task is about to start.
 */
object NotificationChannels {

    const val REMINDERS = "reminders"
    const val FOLLOW_UPS = "follow_ups"
    const val SUMMARIES = "summaries"

    fun register(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    REMINDERS,
                    context.getString(R.string.channel_reminders_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_reminders_description)
                    enableVibration(true)
                },
                NotificationChannel(
                    FOLLOW_UPS,
                    context.getString(R.string.channel_follow_ups_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.channel_follow_ups_description)
                },
                NotificationChannel(
                    SUMMARIES,
                    context.getString(R.string.channel_summaries_name),
                    // Low: a summary is worth reading, never worth interrupting for.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_summaries_description)
                },
            ),
        )
    }
}
