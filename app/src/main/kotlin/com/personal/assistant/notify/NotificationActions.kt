package com.personal.assistant.notify

/** The notification buttons, as the values carried in a broadcast intent. */
object NotificationActions {

    const val EXTRA_ACTION = "com.personal.assistant.extra.ACTION"
    const val EXTRA_TASK_ID = "com.personal.assistant.extra.TASK_ID"
    const val EXTRA_VALUE = "com.personal.assistant.extra.VALUE"
    const val EXTRA_NOTIFICATION_ID = "com.personal.assistant.extra.NOTIFICATION_ID"

    const val START = "start"
    const val SNOOZE = "snooze"
    const val SKIP = "skip"
    const val COMPLETE = "complete"
    const val PARTIAL = "partial"
    const val NOT_DONE = "not_done"
    const val SET_PROGRESS = "set_progress"
    const val MOVE_TO_TOMORROW = "move_to_tomorrow"

    /** How far a snooze pushes the follow-up. */
    const val SNOOZE_MINUTES = 10L
}
