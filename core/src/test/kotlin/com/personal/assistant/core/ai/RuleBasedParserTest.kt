package com.personal.assistant.core.ai

import com.personal.assistant.core.model.MemoryCategory
import com.personal.assistant.core.model.RecurrenceFrequency
import com.personal.assistant.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Every example sentence in the specification, plus the ambiguities it asks the assistant to handle.
 * The reference instant is fixed so these never depend on the machine clock.
 */
class RuleBasedParserTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 17, 10, 0) // Thursday
    private val today: LocalDate = now.toLocalDate()
    private val tomorrow: LocalDate = today.plusDays(1)
    private val parser = RuleBasedParser(
        categoryNames = listOf("Development", "Design", "Study", "Work", "Personal"),
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    private fun create(input: String): TaskDraft {
        val result = parser.parse(input, now)
        val command = result.command
        assertTrue("expected CreateTask for \"$input\" but got $command", command is AssistantCommand.CreateTask)
        return (command as AssistantCommand.CreateTask).draft
    }

    // ------------------------------------------------------------------ English

    @Test
    fun `schedules an explicit English time`() {
        val draft = create("Tomorrow at 10 AM I have to work on the website.")
        assertEquals(tomorrow, draft.date)
        assertEquals(LocalTime.of(10, 0), draft.startTime)
        assertEquals("Work on the website", draft.title)
        assertEquals(30, draft.reminderMinutes)
        assertTrue(!draft.meridiemAssumed)
    }

    @Test
    fun `extracts the action from a reminder request`() {
        val draft = create("Remind me at 5 PM to call Ahmed.")
        assertEquals(LocalTime.of(17, 0), draft.startTime)
        assertEquals("Call Ahmed", draft.title)
    }

    @Test
    fun `undated sentence asks when instead of guessing`() {
        val result = parser.parse("Tomorrow I need to finish the WordPress project.", now)
        val draft = (result.command as AssistantCommand.CreateTask).draft
        assertEquals(tomorrow, draft.date)
        assertNull("no time was given, so none should be invented", draft.startTime)
        assertEquals("Finish the WordPress project", draft.title)
    }

    @Test
    fun `a task with no date at all is a clarifying question`() {
        val result = parser.parse("I need to finish the WordPress project.", now)
        val command = result.command
        assertTrue(command is AssistantCommand.Clarify)
        command as AssistantCommand.Clarify
        assertEquals(MissingField.DATE, command.missing)
        assertEquals("Finish the WordPress project", command.pendingDraft?.title)
    }

    @Test
    fun `duration without a start time keeps the duration`() {
        val draft = create("I need two hours tomorrow for studying.")
        assertEquals(tomorrow, draft.date)
        assertEquals(120, draft.durationMinutes)
        assertNull("\"two hours\" must not be read as 2 AM", draft.startTime)
        assertTrue(draft.title.contains("studying", ignoreCase = true))
    }

    @Test
    fun `parses a time range`() {
        val draft = create("Website development from 4 PM to 6 PM tomorrow")
        assertEquals(tomorrow, draft.date)
        assertEquals(LocalTime.of(16, 0), draft.startTime)
        assertEquals(LocalTime.of(18, 0), draft.endTime)
        assertEquals(120, draft.durationMinutes)
    }

    @Test
    fun `parses a 24 hour clock without assuming a meridiem`() {
        val draft = create("Tomorrow 17:00 client call")
        assertEquals(LocalTime.of(17, 0), draft.startTime)
        assertTrue(!draft.meridiemAssumed)
    }

    @Test
    fun `honours an explicit reminder lead time`() {
        val draft = create("Tomorrow at 3 PM gym, remind me 10 minutes before")
        assertEquals(10, draft.reminderMinutes)
        assertEquals(LocalTime.of(15, 0), draft.startTime)
    }

    @Test
    fun `detects urgency`() {
        val draft = create("Urgent: send the invoice tomorrow at 9 AM")
        assertEquals(com.personal.assistant.core.model.Priority.URGENT, draft.priority)
    }

    @Test
    fun `files a website task under development`() {
        val draft = create("Tomorrow at 4 PM website work")
        assertEquals("Development", draft.categoryName)
    }

    // --------------------------------------------------------------- recurrence

    @Test
    fun `creates a weekly series`() {
        val draft = create("Every Monday at 9 AM I work on my AI project.")
        val recurrence = draft.recurrence
        assertNotNull("expected a recurrence", recurrence)
        requireNotNull(recurrence)
        assertEquals(RecurrenceFrequency.WEEKLY, recurrence.frequency)
        assertEquals(setOf(DayOfWeek.MONDAY), recurrence.daysOfWeek)
        assertEquals(LocalTime.of(9, 0), draft.startTime)
    }

    @Test
    fun `creates a multi day weekly series`() {
        val draft = create("Every Monday and Wednesday at 7 PM gym")
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), draft.recurrence?.daysOfWeek)
    }

    @Test
    fun `creates a weekday series`() {
        val draft = create("Every weekday at 9 AM stand-up")
        assertEquals(RecurrenceFrequency.WEEKLY, draft.recurrence?.frequency)
        assertEquals(5, draft.recurrence?.daysOfWeek?.size)
    }

    @Test
    fun `creates a daily series from roman urdu`() {
        val draft = create("Har roz 6 baje subah walk")
        assertEquals(RecurrenceFrequency.DAILY, draft.recurrence?.frequency)
        assertEquals(LocalTime.of(6, 0), draft.startTime)
    }

    // -------------------------------------------------------------- roman urdu

    @Test
    fun `parses roman urdu with baje`() {
        val draft = create("Kal 4 baje website ka kaam karna hai.")
        assertEquals(tomorrow, draft.date)
        assertEquals(LocalTime.of(16, 0), draft.startTime)
        assertTrue("PM was inferred, so it must be flagged", draft.meridiemAssumed)
        assertTrue("kal is direction-ambiguous", draft.dateDirectionAssumed)
        assertTrue(draft.title.contains("website", ignoreCase = true))
    }

    @Test
    fun `parses the specification's roman urdu reminder example`() {
        val draft = create("Kal 5 baje mujhe client ko call karne ka reminder dena.")
        assertEquals(tomorrow, draft.date)
        assertEquals(LocalTime.of(17, 0), draft.startTime)
        assertTrue(draft.title.contains("client", ignoreCase = true))
    }

    @Test
    fun `resolves a day part word to the afternoon`() {
        val draft = create("Kal shaam 7 baje meeting")
        assertEquals(LocalTime.of(19, 0), draft.startTime)
        assertTrue("shaam states the half of the day, so nothing was assumed", !draft.meridiemAssumed)
    }

    @Test
    fun `resolves a morning day part word`() {
        val draft = create("Kal subah 8 baje doctor")
        assertEquals(LocalTime.of(8, 0), draft.startTime)
    }

    @Test
    fun `parses roman urdu duration`() {
        val draft = create("Kal do ghante study karna hai")
        assertEquals(120, draft.durationMinutes)
        assertEquals(tomorrow, draft.date)
    }

    // -------------------------------------------------------------- urdu script

    @Test
    fun `parses mixed urdu and english`() {
        val draft = create("Tomorrow 5 PM مجھے client کو call کرنا ہے.")
        assertEquals(tomorrow, draft.date)
        assertEquals(LocalTime.of(17, 0), draft.startTime)
        assertTrue(draft.title.contains("client", ignoreCase = true))
    }

    @Test
    fun `parses urdu script time with urdu digits`() {
        // "کل ۴ بجے میٹنگ" -- tomorrow, 4 o'clock, meeting, written with Urdu digits.
        val draft = create("کل ۴ بجے میٹنگ")
        assertEquals(tomorrow, draft.date)
        assertEquals(LocalTime.of(16, 0), draft.startTime)
    }

    // ------------------------------------------------------------------ queries

    @Test
    fun `answers a plan question`() {
        val query = ask("What do I have tomorrow?")
        assertEquals(QueryKind.PLAN, query.kind)
        assertEquals(DateRange.single(tomorrow), query.range)
    }

    @Test
    fun `answers a missed tasks question`() {
        val query = ask("Show my missed tasks.")
        assertEquals(QueryKind.MISSED, query.kind)
    }

    @Test
    fun `answers a completed today question`() {
        val query = ask("What did I complete today?")
        assertEquals(QueryKind.COMPLETED, query.kind)
        assertEquals(DateRange.single(today), query.range)
    }

    @Test
    fun `reads kal as yesterday in a past question`() {
        val query = ask("Kal main ne kya kiya?")
        assertEquals(today.minusDays(1), query.range.start)
    }

    @Test
    fun `answers a time spent question over the week`() {
        val query = ask("How much time did I spend studying this week?")
        assertEquals(QueryKind.TIME_SPENT, query.kind)
        assertEquals(7, query.range.days)
        assertTrue(query.keywords.any { it.contains("study") })
    }

    @Test
    fun `answers an upcoming question`() {
        val query = ask("Show my upcoming tasks.")
        assertEquals(QueryKind.UPCOMING, query.kind)
        assertEquals(today, query.range.start)
    }

    @Test
    fun `answers a search question`() {
        val query = ask("Show website-related tasks")
        assertTrue(query.keywords.any { it.contains("website") })
    }

    @Test
    fun `answers a weekly report request`() {
        val query = ask("Show my weekly report")
        assertEquals(QueryKind.WEEKLY_REPORT, query.kind)
    }

    private fun ask(input: String): AssistantQuery {
        val command = parser.parse(input, now).command
        assertTrue("expected Ask for \"$input\" but got $command", command is AssistantCommand.Ask)
        return (command as AssistantCommand.Ask).query
    }

    // ------------------------------------------------------- status and updates

    @Test
    fun `reschedules to a new time`() {
        val command = parser.parse("Move my 4 PM task to 6 PM.", now).command
        assertTrue(command is AssistantCommand.RescheduleTask)
        command as AssistantCommand.RescheduleTask
        assertEquals(LocalTime.of(18, 0), command.newTime)
        assertEquals(LocalTime.of(16, 0), command.reference.time)
    }

    @Test
    fun `reschedules by name to another day`() {
        val command = parser.parse("Move my website task to tomorrow at 5.", now).command
        assertTrue(command is AssistantCommand.RescheduleTask)
        command as AssistantCommand.RescheduleTask
        assertEquals(tomorrow, command.newDate)
        assertEquals(LocalTime.of(17, 0), command.newTime)
        assertEquals("website", command.reference.titleQuery)
    }

    @Test
    fun `marks a task complete`() {
        val command = parser.parse("Website development is done", now).command
        assertTrue(command is AssistantCommand.ChangeStatus)
        assertEquals(TaskStatus.COMPLETED, (command as AssistantCommand.ChangeStatus).status)
    }

    @Test
    fun `reads a partial completion as progress`() {
        val command = parser.parse("I've finished about half.", now).command
        assertTrue("expected SetProgress but got $command", command is AssistantCommand.SetProgress)
        assertEquals(50, (command as AssistantCommand.SetProgress).percent)
    }

    @Test
    fun `reads an explicit percentage`() {
        val command = parser.parse("Website work is 75% done", now).command
        assertEquals(75, (command as AssistantCommand.SetProgress).percent)
    }

    @Test
    fun `asks for a value when progress has no number`() {
        val command = parser.parse("Still working", now).command
        assertTrue(command is AssistantCommand.Clarify)
        assertEquals(MissingField.PROGRESS_VALUE, (command as AssistantCommand.Clarify).missing)
    }

    @Test
    fun `cancels a task`() {
        val command = parser.parse("Cancel the gym task", now).command
        assertEquals(TaskStatus.CANCELLED, (command as AssistantCommand.ChangeStatus).status)
    }

    // ------------------------------------------------------------------- memory

    @Test
    fun `stores a habit as a routine memory`() {
        val command = parser.parse("I usually work on my WordPress projects at night.", now).command
        assertTrue("a habit must not become a task: got $command", command is AssistantCommand.RememberFact)
        command as AssistantCommand.RememberFact
        assertEquals(MemoryCategory.ROUTINE, command.category)
        assertTrue(command.content.contains("WordPress"))
    }

    @Test
    fun `stores an explicit remember request`() {
        val command = parser.parse("Remember that my client prefers Urdu emails", now).command
        assertTrue(command is AssistantCommand.RememberFact)
        assertTrue((command as AssistantCommand.RememberFact).content.contains("client"))
    }

    // --------------------------------------------------------------- edge cases

    @Test
    fun `empty input is unsupported rather than a crash`() {
        assertTrue(parser.parse("   ", now).command is AssistantCommand.Unsupported)
    }

    @Test
    fun `greeting is small talk`() {
        assertTrue(parser.parse("Hello", now).command is AssistantCommand.SmallTalk)
    }

    @Test
    fun `an impossible date does not throw`() {
        val result = parser.parse("Meeting on 31 February at 4 PM", now)
        // February 31 cannot be resolved, so the time stands and the date falls back to the clock.
        assertTrue(result.command is AssistantCommand.CreateTask || result.command is AssistantCommand.Clarify)
    }

    @Test
    fun `a time already past today rolls to tomorrow`() {
        val draft = create("At 8 AM gym") // reference clock is 10:00
        assertEquals(tomorrow, draft.date)
        assertTrue(draft.dateInferred)
    }

    @Test
    fun `a time still ahead today stays today`() {
        val draft = create("At 4 PM website work")
        assertEquals(today, draft.date)
    }

    @Test
    fun `assumed meridiem lowers confidence`() {
        val assumed = parser.parse("Kal 4 baje website ka kaam", now)
        val explicit = parser.parse("Kal 4 PM website ka kaam", now)
        assertTrue(assumed.confidence < explicit.confidence)
    }
}
