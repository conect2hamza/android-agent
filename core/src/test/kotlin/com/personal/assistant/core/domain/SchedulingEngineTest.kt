package com.personal.assistant.core.domain

import com.personal.assistant.core.TestTasks
import com.personal.assistant.core.model.Recurrence
import com.personal.assistant.core.model.RecurrenceFrequency
import com.personal.assistant.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class SchedulingEngineTest {

    private val today: LocalDate = TestTasks.NOW.toLocalDate()

    @Test
    fun `reports the overlap from the specification example`() {
        val website = TestTasks.task(1, "Website Development", start = LocalTime.of(14, 0), end = LocalTime.of(16, 0))
        val study = TestTasks.task(2, "Study", start = LocalTime.of(15, 0), end = LocalTime.of(17, 0))
        val conflicts = SchedulingEngine.detectConflicts(listOf(website, study))
        assertEquals(1, conflicts.size)
        assertEquals(60, conflicts.first().overlapMinutes)
        assertTrue(conflicts.first().describe().contains("one hour"))
    }

    @Test
    fun `back to back tasks do not conflict`() {
        val first = TestTasks.task(1, start = LocalTime.of(14, 0), end = LocalTime.of(15, 0))
        val second = TestTasks.task(2, start = LocalTime.of(15, 0), end = LocalTime.of(16, 0))
        assertTrue(SchedulingEngine.detectConflicts(listOf(first, second)).isEmpty())
    }

    @Test
    fun `all day tasks are never reported as conflicts`() {
        val allDay = TestTasks.task(1, "Reading")
        val other = TestTasks.task(2, start = LocalTime.of(14, 0), end = LocalTime.of(16, 0))
        assertTrue(SchedulingEngine.detectConflicts(listOf(allDay, other)).isEmpty())
    }

    @Test
    fun `cancelled tasks are never reported as conflicts`() {
        val cancelled = TestTasks.task(
            1,
            start = LocalTime.of(14, 0),
            end = LocalTime.of(16, 0),
            status = TaskStatus.CANCELLED,
        )
        val other = TestTasks.task(2, start = LocalTime.of(15, 0), end = LocalTime.of(17, 0))
        assertTrue(SchedulingEngine.detectConflicts(listOf(cancelled, other)).isEmpty())
    }

    @Test
    fun `conflicts for a candidate exclude unrelated pairs`() {
        val a = TestTasks.task(1, start = LocalTime.of(9, 0), end = LocalTime.of(11, 0))
        val b = TestTasks.task(2, start = LocalTime.of(10, 0), end = LocalTime.of(12, 0))
        val candidate = TestTasks.task(3, start = LocalTime.of(15, 0), end = LocalTime.of(16, 0))
        assertTrue(SchedulingEngine.conflictsFor(candidate, listOf(a, b)).isEmpty())
    }

    @Test
    fun `expands a daily series every third day in phase with its start`() {
        val recurrence = Recurrence(
            frequency = RecurrenceFrequency.DAILY,
            interval = 3,
            startDate = LocalDate.of(2026, 9, 1),
        )
        val dates = SchedulingEngine.occurrences(recurrence, LocalDate.of(2026, 9, 10)..LocalDate.of(2026, 9, 20))
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 10),
                LocalDate.of(2026, 9, 13),
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 19),
            ),
            dates,
        )
    }

    @Test
    fun `expands a weekly series on the chosen weekdays`() {
        val recurrence = Recurrence(
            frequency = RecurrenceFrequency.WEEKLY,
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            startDate = LocalDate.of(2026, 9, 14), // a Monday
        )
        val dates = SchedulingEngine.occurrences(recurrence, LocalDate.of(2026, 9, 14)..LocalDate.of(2026, 9, 27))
        assertEquals(4, dates.size)
        assertTrue(dates.all { it.dayOfWeek == DayOfWeek.MONDAY || it.dayOfWeek == DayOfWeek.WEDNESDAY })
    }

    @Test
    fun `expands a fortnightly series`() {
        val recurrence = Recurrence(
            frequency = RecurrenceFrequency.WEEKLY,
            interval = 2,
            daysOfWeek = setOf(DayOfWeek.FRIDAY),
            startDate = LocalDate.of(2026, 9, 4), // a Friday
        )
        val dates = SchedulingEngine.occurrences(recurrence, LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 10, 3))
        assertEquals(
            listOf(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 18), LocalDate.of(2026, 10, 2)),
            dates,
        )
    }

    @Test
    fun `monthly series on the 31st falls back to the last day of shorter months`() {
        val recurrence = Recurrence(
            frequency = RecurrenceFrequency.MONTHLY,
            dayOfMonth = 31,
            startDate = LocalDate.of(2026, 1, 31),
        )
        val dates = SchedulingEngine.occurrences(recurrence, LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 4, 30))
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
            ),
            dates,
        )
    }

    @Test
    fun `series stops at its end date`() {
        val recurrence = Recurrence(
            frequency = RecurrenceFrequency.DAILY,
            startDate = LocalDate.of(2026, 9, 1),
            endDate = LocalDate.of(2026, 9, 3),
        )
        val dates = SchedulingEngine.occurrences(recurrence, LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 9, 30))
        assertEquals(3, dates.size)
    }

    @Test
    fun `finds a free slot before the first booking`() {
        val booked = TestTasks.task(1, start = LocalTime.of(11, 0), end = LocalTime.of(12, 0))
        val slot = SchedulingEngine.firstFreeSlot(today, 60, listOf(booked))
        assertNotNull(slot)
        assertEquals(LocalTime.of(9, 0), slot?.toLocalTime())
    }

    @Test
    fun `finds a free slot after a booking that blocks the morning`() {
        val booked = TestTasks.task(1, start = LocalTime.of(9, 0), end = LocalTime.of(12, 0))
        val slot = SchedulingEngine.firstFreeSlot(today, 60, listOf(booked))
        assertEquals(LocalTime.of(12, 0), slot?.toLocalTime())
    }

    @Test
    fun `returns no slot when the day is full`() {
        val booked = TestTasks.task(1, start = LocalTime.of(9, 0), end = LocalTime.of(22, 0))
        assertNull(SchedulingEngine.firstFreeSlot(today, 60, listOf(booked)))
    }
}
