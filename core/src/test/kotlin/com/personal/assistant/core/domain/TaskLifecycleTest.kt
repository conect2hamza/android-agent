package com.personal.assistant.core.domain

import com.personal.assistant.core.TestTasks
import com.personal.assistant.core.model.ActivityEvent
import com.personal.assistant.core.model.ActivityLog
import com.personal.assistant.core.model.ReminderKind
import com.personal.assistant.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TaskLifecycleTest {

    private val now = TestTasks.NOW

    @Test
    fun `follows the specification's lifecycle`() {
        assertTrue(TaskStateMachine.canTransition(TaskStatus.PLANNED, TaskStatus.UPCOMING))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.UPCOMING, TaskStatus.STARTED))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.STARTED, TaskStatus.IN_PROGRESS))
        assertTrue(TaskStateMachine.canTransition(TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED))
    }

    @Test
    fun `a cancelled task cannot be completed by a stale notification action`() {
        assertFalse(TaskStateMachine.canTransition(TaskStatus.CANCELLED, TaskStatus.COMPLETED))
        val cancelled = TestTasks.task(status = TaskStatus.CANCELLED)
        assertTrue(TaskStateMachine.apply(cancelled, TaskStatus.COMPLETED, now).isFailure)
    }

    @Test
    fun `completing a task fills in the completion stamp and pins progress`() {
        val task = TestTasks.task(status = TaskStatus.IN_PROGRESS, progress = 40)
        val completed = TaskStateMachine.apply(task, TaskStatus.COMPLETED, now).getOrThrow()
        assertEquals(100, completed.progress)
        assertEquals(now, completed.completedAt)
    }

    @Test
    fun `reopening a completed task clears the completion stamp`() {
        val task = TestTasks.task(status = TaskStatus.COMPLETED, progress = 100)
        val reopened = TaskStateMachine.apply(task, TaskStatus.IN_PROGRESS, now).getOrThrow()
        assertNull(reopened.completedAt)
    }

    @Test
    fun `the clock moves a task into upcoming inside its reminder window`() {
        val task = TestTasks.task(start = now.toLocalTime().plusMinutes(20), reminderMinutes = 30)
        assertEquals(TaskStatus.UPCOMING, TaskStateMachine.deriveForClock(task, now))
    }

    @Test
    fun `the clock marks an untouched past task as missed`() {
        val task = TestTasks.task(start = LocalTime.of(7, 0), end = LocalTime.of(8, 0))
        assertEquals(TaskStatus.MISSED, TaskStateMachine.deriveForClock(task, now))
    }

    @Test
    fun `the clock never overrides what the user already did`() {
        val done = TestTasks.task(start = LocalTime.of(7, 0), end = LocalTime.of(8, 0), status = TaskStatus.COMPLETED)
        assertEquals(TaskStatus.COMPLETED, TaskStateMachine.deriveForClock(done, now))
        val running = TestTasks.task(
            start = LocalTime.of(7, 0),
            end = LocalTime.of(8, 0),
            status = TaskStatus.IN_PROGRESS,
        )
        assertEquals(TaskStatus.IN_PROGRESS, TaskStateMachine.deriveForClock(running, now))
    }

    @Test
    fun `an all day task is only missed once the day is over`() {
        val task = TestTasks.task()
        assertEquals(TaskStatus.PLANNED, TaskStateMachine.deriveForClock(task, now))
        assertEquals(TaskStatus.MISSED, TaskStateMachine.deriveForClock(task, now.plusDays(1)))
    }

    @Test
    fun `progress maps onto a status`() {
        assertEquals(TaskStatus.STARTED, TaskStateMachine.statusForProgress(0))
        assertEquals(TaskStatus.IN_PROGRESS, TaskStateMachine.statusForProgress(50))
        assertEquals(TaskStatus.COMPLETED, TaskStateMachine.statusForProgress(100))
    }

    // ------------------------------------------------------------ time tracking

    @Test
    fun `sums active time across a pause`() {
        val logs = listOf(
            log(ActivityEvent.STARTED, 9, 0),
            log(ActivityEvent.PAUSED, 9, 45),
            log(ActivityEvent.RESUMED, 10, 15),
            log(ActivityEvent.COMPLETED, 11, 5),
        )
        val tracked = TimeTracker.tracked(logs, now.withHour(12))
        assertEquals(45 + 50, tracked.activeMinutes)
        assertFalse(tracked.running)
        assertNotNull(tracked.firstStart)
    }

    @Test
    fun `counts a still running task up to now`() {
        val logs = listOf(log(ActivityEvent.STARTED, 9, 30))
        val tracked = TimeTracker.tracked(logs, now) // now is 10:00
        assertEquals(30, tracked.activeMinutes)
        assertTrue(tracked.running)
    }

    @Test
    fun `ignores events that do not bound a session`() {
        val logs = listOf(
            log(ActivityEvent.CREATED, 8, 0),
            log(ActivityEvent.REMINDED, 8, 30),
            log(ActivityEvent.STARTED, 9, 0),
            log(ActivityEvent.PROGRESS_UPDATED, 9, 20),
            log(ActivityEvent.COMPLETED, 9, 40),
        )
        assertEquals(40, TimeTracker.tracked(logs, now).activeMinutes)
    }

    @Test
    fun `formats durations the way the reports read`() {
        assertEquals("5h 20m", TimeTracker.format(320))
        assertEquals("2h", TimeTracker.format(120))
        assertEquals("35m", TimeTracker.format(35))
    }

    // ---------------------------------------------------------------- reminders

    @Test
    fun `plans the full follow-up chain for a two hour task`() {
        val task = TestTasks.task(
            start = LocalTime.of(16, 0),
            end = LocalTime.of(18, 0),
            reminderMinutes = 30,
        )
        val kinds = ReminderPlanner.plan(task, now).map { it.kind }
        assertEquals(
            listOf(
                ReminderKind.LEAD_UP,
                ReminderKind.START,
                ReminderKind.PROGRESS_CHECK,
                ReminderKind.COMPLETION_CHECK,
            ),
            kinds,
        )
    }

    @Test
    fun `skips the progress check on a short task`() {
        val task = TestTasks.task(start = LocalTime.of(16, 0), end = LocalTime.of(16, 30))
        val kinds = ReminderPlanner.plan(task, now).map { it.kind }
        assertFalse(kinds.contains(ReminderKind.PROGRESS_CHECK))
    }

    @Test
    fun `plans nothing for a finished task`() {
        val task = TestTasks.task(start = LocalTime.of(16, 0), status = TaskStatus.COMPLETED)
        assertTrue(ReminderPlanner.plan(task, now).isEmpty())
    }

    @Test
    fun `plans nothing in the past`() {
        val task = TestTasks.task(start = LocalTime.of(7, 0), end = LocalTime.of(8, 0))
        assertTrue(ReminderPlanner.plan(task, now).isEmpty())
    }

    @Test
    fun `request codes are stable per task and kind and never collide`() {
        val first = ReminderPlanner.requestCode(42, ReminderKind.LEAD_UP)
        assertEquals(first, ReminderPlanner.requestCode(42, ReminderKind.LEAD_UP))
        val codes = (1L..50L).flatMap { id ->
            ReminderKind.entries.map { ReminderPlanner.requestCode(id, it) }
        }
        assertEquals(codes.size, codes.distinct().size)
    }

    private fun log(event: ActivityEvent, hour: Int, minute: Int) = ActivityLog(
        taskId = 1,
        event = event,
        timestamp = now.withHour(hour).withMinute(minute),
    )
}
