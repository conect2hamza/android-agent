package com.personal.assistant.core.transfer

import com.personal.assistant.core.TestTasks
import com.personal.assistant.core.model.ActivityEvent
import com.personal.assistant.core.model.ActivityLog
import com.personal.assistant.core.model.Category
import com.personal.assistant.core.model.Conversation
import com.personal.assistant.core.model.Memory
import com.personal.assistant.core.model.MemoryCategory
import com.personal.assistant.core.model.Message
import com.personal.assistant.core.model.Recurrence
import com.personal.assistant.core.model.RecurrenceFrequency
import com.personal.assistant.core.model.Sender
import com.personal.assistant.core.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class ExportCodecTest {

    private val now = TestTasks.NOW

    private fun bundle() = ExportBundle(
        exportedAt = now,
        categories = listOf(Category(id = 1, name = "Development", colorArgb = 0xFF3B82F6.toInt())),
        recurrences = listOf(
            Recurrence(
                id = 5,
                frequency = RecurrenceFrequency.WEEKLY,
                daysOfWeek = setOf(DayOfWeek.MONDAY),
                startDate = now.toLocalDate(),
            ),
        ),
        tasks = listOf(
            TestTasks.task(
                id = 10,
                title = "Website work",
                start = LocalTime.of(16, 0),
                end = LocalTime.of(18, 0),
                status = TaskStatus.COMPLETED,
                progress = 100,
                categoryId = 1,
            ),
        ),
        activityLogs = listOf(
            ActivityLog(id = 20, taskId = 10, event = ActivityEvent.STARTED, timestamp = now),
        ),
        memories = listOf(
            Memory(
                id = 30,
                content = "Works on WordPress at night",
                category = MemoryCategory.ROUTINE,
                importance = 4,
                createdAt = now,
                updatedAt = now,
            ),
        ),
        preferences = mapOf("theme" to "dark", "default_reminder" to "30"),
        conversations = listOf(Conversation(id = 40, createdAt = now, updatedAt = now)),
        messages = listOf(
            Message(id = 50, conversationId = 40, sender = Sender.USER, content = "Kal 4 baje", timestamp = now),
        ),
    )

    @Test
    fun `round trips a full bundle`() {
        val result = ExportCodec.decode(ExportCodec.encode(bundle()))
        assertTrue(result.warnings.toString(), result.succeeded)
        val restored = result.bundle!!
        assertEquals(1, restored.tasks.size)
        assertEquals("Website work", restored.tasks.first().title)
        assertEquals(LocalTime.of(16, 0), restored.tasks.first().startTime)
        assertEquals(TaskStatus.COMPLETED, restored.tasks.first().status)
        assertEquals(setOf(DayOfWeek.MONDAY), restored.recurrences.first().daysOfWeek)
        assertEquals("Works on WordPress at night", restored.memories.first().content)
        assertEquals("dark", restored.preferences["theme"])
        assertEquals(1, restored.messages.size)
        assertEquals(now, restored.exportedAt)
    }

    @Test
    fun `refuses a file from a newer version`() {
        val result = ExportCodec.decode("""{"version":99,"exported_at":"$now","tasks":[]}""")
        assertFalse(result.succeeded)
        assertTrue(result.errors.first().contains("newer version"))
        assertNull(result.bundle)
    }

    @Test
    fun `refuses a file with no version`() {
        assertFalse(ExportCodec.decode("""{"tasks":[]}""").succeeded)
    }

    @Test
    fun `refuses input that is not json`() {
        val result = ExportCodec.decode("<html>nope</html>")
        assertFalse(result.succeeded)
        assertTrue(result.errors.first().contains("not valid JSON"))
    }

    @Test
    fun `skips a corrupt row instead of losing the file`() {
        val json = """
            {"version":1,"exported_at":"$now","tasks":[
              {"id":1,"title":"Good task","date":"2026-09-17"},
              {"id":2,"date":"2026-09-18"},
              {"id":3,"title":"Bad date","date":"not-a-date"},
              {"id":4,"title":"Also good","date":"2026-09-19"}
            ]}
        """.trimIndent()
        val result = ExportCodec.decode(json)
        assertTrue(result.succeeded)
        assertEquals(2, result.bundle!!.tasks.size)
        assertEquals(2, result.warnings.size)
        assertTrue(result.summary().contains("2 skipped"))
    }

    @Test
    fun `clamps an out of range progress value`() {
        val json = """{"version":1,"exported_at":"$now","tasks":[
            {"id":1,"title":"T","date":"2026-09-17","progress":5000}]}"""
        assertEquals(100, ExportCodec.decode(json).bundle!!.tasks.first().progress)
    }

    @Test
    fun `drops an activity log with no matching task`() {
        val json = """{"version":1,"exported_at":"$now",
            "tasks":[{"id":1,"title":"T","date":"2026-09-17"}],
            "activity_logs":[
              {"id":1,"task_id":1,"event":"STARTED","timestamp":"$now"},
              {"id":2,"task_id":999,"event":"STARTED","timestamp":"$now"}
            ]}"""
        val result = ExportCodec.decode(json)
        assertEquals(1, result.bundle!!.activityLogs.size)
        assertTrue(result.warnings.any { it.contains("no matching task") })
    }

    @Test
    fun `drops a message with no matching conversation`() {
        val json = """{"version":1,"exported_at":"$now",
            "conversations":[{"id":1,"created_at":"$now","updated_at":"$now"}],
            "messages":[
              {"id":1,"conversation_id":1,"sender":"USER","content":"hi","timestamp":"$now"},
              {"id":2,"conversation_id":77,"sender":"USER","content":"hi","timestamp":"$now"}
            ]}"""
        val result = ExportCodec.decode(json)
        assertEquals(1, result.bundle!!.messages.size)
    }

    @Test
    fun `an unknown status falls back to planned rather than failing`() {
        val json = """{"version":1,"exported_at":"$now","tasks":[
            {"id":1,"title":"T","date":"2026-09-17","status":"WAT"}]}"""
        assertEquals(TaskStatus.PLANNED, ExportCodec.decode(json).bundle!!.tasks.first().status)
    }

    @Test
    fun `csv export quotes commas and newlines`() {
        val task = TestTasks.task(id = 1, title = "Call Ahmed, then Sara")
        val csv = ExportCodec.tasksToCsv(listOf(task))
        assertTrue(csv.contains("\"Call Ahmed, then Sara\""))
        assertEquals(2, csv.trim().lines().size)
    }

    @Test
    fun `csv export neutralises a value a spreadsheet would execute`() {
        val task = TestTasks.task(id = 1, title = "=HYPERLINK(\"http://x\")")
        val csv = ExportCodec.tasksToCsv(listOf(task))
        // Prefixed with an apostrophe so the cell stays text, and the embedded quotes are doubled.
        assertTrue(csv.contains("'=HYPERLINK"))
        assertFalse(csv.lines()[1].startsWith("="))
    }

    @Test
    fun `an empty bundle round trips`() {
        val empty = ExportBundle(exportedAt = now)
        val result = ExportCodec.decode(ExportCodec.encode(empty))
        assertTrue(result.succeeded)
        assertTrue(result.bundle!!.tasks.isEmpty())
    }
}
