package com.personal.assistant.core.ai

import com.personal.assistant.core.model.Priority
import com.personal.assistant.core.model.RecurrenceFrequency
import com.personal.assistant.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The trust boundary between the language model and the database. Every test here is a thing a small
 * quantised model actually does.
 */
class CommandCodecTest {

    private val now = LocalDateTime.of(2026, 9, 17, 10, 0)
    private val context = AiContext(
        now = now,
        categoryNames = listOf("Development", "Study"),
        firstDayOfWeek = DayOfWeek.MONDAY,
    )

    @Test
    fun `decodes a well formed create_task`() {
        val command = CommandCodec.decode(
            """{"intent":"create_task","title":"Work on website","date_phrase":"tomorrow","time_phrase":"10 am","reminder_minutes":15}""",
            context,
        )
        assertTrue(command is AssistantCommand.CreateTask)
        val draft = (command as AssistantCommand.CreateTask).draft
        assertEquals(now.toLocalDate().plusDays(1), draft.date)
        assertEquals(LocalTime.of(10, 0), draft.startTime)
        assertEquals(15, draft.reminderMinutes)
    }

    @Test
    fun `tolerates prose and a code fence around the object`() {
        val command = CommandCodec.decode(
            "Of course!\n```json\n{\"intent\":\"create_task\",\"title\":\"Gym\",\"date_phrase\":\"tomorrow\"}\n```\n",
            context,
        )
        assertTrue(command is AssistantCommand.CreateTask)
    }

    @Test
    fun `recomputes the date instead of trusting the model's arithmetic`() {
        // The model was told today is 2026-09-17 and still answered with the wrong ISO date.
        val command = CommandCodec.decode(
            """{"intent":"create_task","title":"Gym","date_phrase":"tomorrow","date":"2025-01-02"}""",
            context,
        )
        val draft = (command as AssistantCommand.CreateTask).draft
        assertEquals(now.toLocalDate().plusDays(1), draft.date)
    }

    @Test
    fun `an impossible date cannot reach the database`() {
        val command = CommandCodec.decode(
            """{"intent":"create_task","title":"Gym","date_phrase":"2027-13-45","time_phrase":"9 am"}""",
            context,
        )
        // The date is unusable, so the time decides the day rather than the invalid value being stored.
        val draft = (command as AssistantCommand.CreateTask).draft
        assertTrue(draft.date == now.toLocalDate() || draft.date == now.toLocalDate().plusDays(1))
    }

    @Test
    fun `an unknown intent is rejected outright`() {
        assertNull(CommandCodec.decode("""{"intent":"drop_all_tables"}""", context))
        assertNull(CommandCodec.decode("""{"intent":"exec","command":"rm -rf"}""", context))
    }

    @Test
    fun `missing json is rejected`() {
        assertNull(CommandCodec.decode("I cannot help with that.", context))
        assertNull(CommandCodec.decode("", context))
    }

    @Test
    fun `progress is clamped into range`() {
        val command = CommandCodec.decode("""{"intent":"set_progress","progress":4000}""", context)
        assertEquals(100, (command as AssistantCommand.SetProgress).percent)
        val negative = CommandCodec.decode("""{"intent":"set_progress","progress":-20}""", context)
        assertEquals(0, (negative as AssistantCommand.SetProgress).percent)
    }

    @Test
    fun `a category the user does not have is dropped rather than created`() {
        val command = CommandCodec.decode(
            """{"intent":"create_task","title":"Gym","date_phrase":"tomorrow","category":"Fitness"}""",
            context,
        )
        assertNull((command as AssistantCommand.CreateTask).draft.categoryName)
    }

    @Test
    fun `a known category is matched case insensitively`() {
        val command = CommandCodec.decode(
            """{"intent":"create_task","title":"Bug fix","date_phrase":"tomorrow","category":"development"}""",
            context,
        )
        assertEquals("Development", (command as AssistantCommand.CreateTask).draft.categoryName)
    }

    @Test
    fun `an out of range reminder is replaced by the default`() {
        val command = CommandCodec.decode(
            """{"intent":"create_task","title":"Gym","date_phrase":"tomorrow","time_phrase":"9 am","reminder_minutes":99999}""",
            context,
        )
        assertEquals(30, (command as AssistantCommand.CreateTask).draft.reminderMinutes)
    }

    @Test
    fun `decodes a recurrence`() {
        val command = CommandCodec.decode(
            """{"intent":"create_task","title":"AI project","time_phrase":"9 am","recurrence":{"frequency":"WEEKLY","interval":1,"days_of_week":["MONDAY","THURSDAY"]}}""",
            context,
        )
        val recurrence = (command as AssistantCommand.CreateTask).draft.recurrence
        assertEquals(RecurrenceFrequency.WEEKLY, recurrence?.frequency)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), recurrence?.daysOfWeek)
    }

    @Test
    fun `a garbage weekday is ignored, not guessed`() {
        val command = CommandCodec.decode(
            """{"intent":"create_task","title":"Gym","date_phrase":"tomorrow","recurrence":{"frequency":"WEEKLY","days_of_week":["FUNDAY"]}}""",
            context,
        )
        assertTrue((command as AssistantCommand.CreateTask).draft.recurrence?.daysOfWeek?.isEmpty() == true)
    }

    @Test
    fun `only reportable statuses are accepted`() {
        val ok = CommandCodec.decode("""{"intent":"change_status","status":"COMPLETED","target":"gym"}""", context)
        assertEquals(TaskStatus.COMPLETED, (ok as AssistantCommand.ChangeStatus).status)
        // UPCOMING is derived from the clock, not something the model may assert.
        assertNull(CommandCodec.decode("""{"intent":"change_status","status":"UPCOMING"}""", context))
    }

    @Test
    fun `decodes a query with a resolved range`() {
        val command = CommandCodec.decode(
            """{"intent":"query","query_kind":"MISSED","date_phrase":"yesterday"}""",
            context,
        )
        val query = (command as AssistantCommand.Ask).query
        assertEquals(QueryKind.MISSED, query.kind)
        assertEquals(now.toLocalDate().minusDays(1), query.range.start)
    }

    @Test
    fun `a weekly report query spans a week`() {
        val command = CommandCodec.decode("""{"intent":"query","query_kind":"WEEKLY_REPORT"}""", context)
        assertEquals(7, (command as AssistantCommand.Ask).query.range.days)
    }

    @Test
    fun `control characters are stripped from text fields`() {
        val command = CommandCodec.decode(
            "{\"intent\":\"create_task\",\"title\":\"Gym\\u0007\\u0000 session\",\"date_phrase\":\"tomorrow\"}",
            context,
        )
        val title = (command as AssistantCommand.CreateTask).draft.title
        assertTrue(title.none { it.code < 0x20 })
        assertEquals("Gym session", title)
    }

    @Test
    fun `an absurdly long title is truncated`() {
        val long = "a".repeat(5000)
        val command = CommandCodec.decode(
            """{"intent":"create_task","title":"$long","date_phrase":"tomorrow"}""",
            context,
        )
        assertTrue((command as AssistantCommand.CreateTask).draft.title.length <= 500)
    }

    @Test
    fun `a create_task with no title asks instead of inventing one`() {
        val command = CommandCodec.decode("""{"intent":"create_task","date_phrase":"tomorrow"}""", context)
        assertTrue(command is AssistantCommand.Clarify)
        assertEquals(MissingField.TITLE, (command as AssistantCommand.Clarify).missing)
    }

    @Test
    fun `priority is parsed and defaults sensibly`() {
        val urgent = CommandCodec.decode(
            """{"intent":"create_task","title":"Invoice","date_phrase":"tomorrow","priority":"urgent"}""",
            context,
        )
        assertEquals(Priority.URGENT, (urgent as AssistantCommand.CreateTask).draft.priority)
        val nonsense = CommandCodec.decode(
            """{"intent":"create_task","title":"Invoice","date_phrase":"tomorrow","priority":"SUPER_DUPER"}""",
            context,
        )
        assertEquals(Priority.NORMAL, (nonsense as AssistantCommand.CreateTask).draft.priority)
    }

    @Test
    fun `a remember command needs actual content`() {
        assertNull(CommandCodec.decode("""{"intent":"remember","memory":"a"}""", context))
        val ok = CommandCodec.decode(
            """{"intent":"remember","memory":"Works on WordPress at night","memory_category":"ROUTINE","importance":9}""",
            context,
        )
        assertEquals(5, (ok as AssistantCommand.RememberFact).importance)
    }
}
