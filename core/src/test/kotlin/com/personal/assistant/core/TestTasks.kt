package com.personal.assistant.core

import com.personal.assistant.core.model.Priority
import com.personal.assistant.core.model.Task
import com.personal.assistant.core.model.TaskStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Shared builder so test bodies stay about the behaviour under test. */
object TestTasks {

    val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 17, 10, 0)

    fun task(
        id: Long = 1,
        title: String = "Task $id",
        date: LocalDate = NOW.toLocalDate(),
        start: LocalTime? = null,
        end: LocalTime? = null,
        durationMinutes: Int? = null,
        status: TaskStatus = TaskStatus.PLANNED,
        progress: Int = 0,
        categoryId: Long? = null,
        priority: Priority = Priority.NORMAL,
        reminderMinutes: Int? = 30,
    ): Task = Task(
        id = id,
        title = title,
        date = date,
        startTime = start,
        endTime = end,
        durationMinutes = durationMinutes,
        status = status,
        progress = progress,
        categoryId = categoryId,
        priority = priority,
        reminderMinutes = reminderMinutes,
        createdAt = NOW.minusDays(1),
        updatedAt = NOW.minusDays(1),
        completedAt = if (status == TaskStatus.COMPLETED) NOW else null,
    )
}
