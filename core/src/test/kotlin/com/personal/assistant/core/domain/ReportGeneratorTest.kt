package com.personal.assistant.core.domain

import com.personal.assistant.core.TestTasks
import com.personal.assistant.core.ai.DateRange
import com.personal.assistant.core.model.ActivityEvent
import com.personal.assistant.core.model.ActivityLog
import com.personal.assistant.core.model.Category
import com.personal.assistant.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class ReportGeneratorTest {

    private val now = TestTasks.NOW
    private val today: LocalDate = now.toLocalDate()
    private val development = Category(id = 1, name = "Development", colorArgb = 0)
    private val study = Category(id = 2, name = "Study", colorArgb = 0)
    private val generator = ReportGenerator(listOf(development, study))

    @Test
    fun `counts the specification's example day`() {
        val tasks = listOf(
            TestTasks.task(1, status = TaskStatus.COMPLETED),
            TestTasks.task(2, status = TaskStatus.COMPLETED),
            TestTasks.task(3, status = TaskStatus.COMPLETED),
            TestTasks.task(4, status = TaskStatus.COMPLETED),
            TestTasks.task(5, status = TaskStatus.IN_PROGRESS),
            TestTasks.task(6),
            TestTasks.task(7),
            TestTasks.task(8, start = LocalTime.of(7, 0), end = LocalTime.of(8, 0)), // unticked, now past
        )
        val report = generator.daily(today, tasks, emptyList(), now)
        assertEquals(8, report.totals.total)
        assertEquals(4, report.totals.completed)
        assertEquals(1, report.totals.inProgress)
        assertEquals(2, report.totals.pending)
        assertEquals(1, report.totals.missed)
        assertEquals(50.0, report.totals.completionRate, 0.01)
    }

    @Test
    fun `cancelled work is left out of the completion rate`() {
        val tasks = listOf(
            TestTasks.task(1, status = TaskStatus.COMPLETED),
            TestTasks.task(2, status = TaskStatus.CANCELLED),
        )
        val report = generator.daily(today, tasks, emptyList(), now)
        assertEquals(100.0, report.totals.completionRate, 0.01)
    }

    @Test
    fun `a partial completion counts as half`() {
        val tasks = listOf(
            TestTasks.task(1, status = TaskStatus.COMPLETED),
            TestTasks.task(2, status = TaskStatus.PARTIALLY_COMPLETED),
        )
        val report = generator.daily(today, tasks, emptyList(), now)
        assertEquals(75.0, report.totals.completionRate, 0.01)
    }

    @Test
    fun `totals a day of planned and tracked time`() {
        val tasks = listOf(
            TestTasks.task(1, start = LocalTime.of(14, 0), end = LocalTime.of(16, 0)),
            TestTasks.task(2, start = LocalTime.of(16, 0), end = LocalTime.of(17, 0)),
        )
        val logs = listOf(
            ActivityLog(taskId = 1, event = ActivityEvent.STARTED, timestamp = now.withHour(14)),
            ActivityLog(taskId = 1, event = ActivityEvent.COMPLETED, timestamp = now.withHour(15).withMinute(35)),
        )
        val report = generator.daily(today, tasks, logs, now.withHour(18))
        assertEquals(180, report.plannedMinutes)
        assertEquals(95, report.trackedMinutes)
    }

    @Test
    fun `groups time by category`() {
        val tasks = listOf(
            TestTasks.task(1, start = LocalTime.of(9, 0), end = LocalTime.of(11, 0), categoryId = 1),
            TestTasks.task(2, start = LocalTime.of(11, 0), end = LocalTime.of(12, 0), categoryId = 2),
        )
        val report = generator.daily(today, tasks, emptyList(), now)
        assertEquals("Development", report.categories.first().label)
        assertEquals(120, report.categories.first().minutes)
        assertEquals(2, report.categories.size)
    }

    @Test
    fun `builds a timeline from the activity log`() {
        val tasks = listOf(TestTasks.task(1, "Study"))
        val logs = listOf(
            ActivityLog(taskId = 1, event = ActivityEvent.STARTED, timestamp = now.withHour(9)),
            ActivityLog(taskId = 1, event = ActivityEvent.COMPLETED, timestamp = now.withHour(10).withMinute(30)),
        )
        val report = generator.daily(today, tasks, logs, now.withHour(11))
        assertEquals(listOf("Study started", "Study completed"), report.timeline.map { it.label })
    }

    @Test
    fun `a weekly report has one point per day`() {
        val range = DateRange.week(today, DayOfWeek.MONDAY)
        val tasks = range.dates().mapIndexed { index, date ->
            TestTasks.task(index + 1L, date = date, status = TaskStatus.COMPLETED)
        }
        val report = generator.weekly(range, tasks, emptyList(), now)
        assertEquals(7, report.perDay.size)
        assertEquals(7, report.totals.completed)
        assertTrue(report.perDay.all { it.totals.total == 1 })
    }

    @Test
    fun `a monthly report ranks the most active days and trends by week`() {
        val range = DateRange.month(today)
        val tasks = buildList {
            repeat(3) { add(TestTasks.task(it + 1L, date = range.start, status = TaskStatus.COMPLETED)) }
            add(TestTasks.task(10, date = range.start.plusDays(10), status = TaskStatus.COMPLETED))
        }
        val report = generator.monthly(range, tasks, emptyList(), now)
        assertEquals(range.start, report.mostActiveDays.first().date)
        assertTrue(report.weeklyTrend.isNotEmpty())
        // Every day of the month is represented, and the weeks are clipped to the month.
        assertEquals(range.days, report.perDay.size)
        assertTrue(report.weeklyTrend.all { it.first.start >= range.start && it.first.end <= range.end })
    }

    @Test
    fun `an empty day reports a zero rate rather than dividing by zero`() {
        val report = generator.daily(today, emptyList(), emptyList(), now)
        assertEquals(0.0, report.totals.completionRate, 0.001)
        assertEquals(0, report.totals.total)
    }
}
