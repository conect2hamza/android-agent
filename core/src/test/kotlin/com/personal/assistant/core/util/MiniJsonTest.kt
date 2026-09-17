package com.personal.assistant.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `round trips an object`() {
        val original = jsonOf(
            "intent" to "create_task".json(),
            "title" to "Website work".json(),
            "reminder_minutes" to 30.json(),
            "done" to false.json(),
            "tags" to listOf(JsonValue.Str("a"), JsonValue.Str("b")).json(),
        )
        val text = MiniJson.write(original, indent = 2)
        val parsed = MiniJson.parse(text) as JsonValue.Obj
        assertEquals("create_task", parsed.string("intent"))
        assertEquals(30, parsed.int("reminder_minutes"))
        assertEquals(false, parsed.bool("done"))
        assertEquals(2, parsed.array("tags").size)
    }

    @Test
    fun `null values are dropped by the builder and ignored by accessors`() {
        val obj = jsonOf("a" to null, "b" to JsonValue.Null)
        assertNull(obj.string("a"))
        assertNull(obj.string("b"))
    }

    @Test
    fun `escapes and unescapes control characters and quotes`() {
        val original = jsonOf("text" to "line1\nline2\t\"quoted\" \\ back".json())
        val parsed = MiniJson.parse(MiniJson.write(original)) as JsonValue.Obj
        assertEquals("line1\nline2\t\"quoted\" \\ back", parsed.string("text"))
    }

    @Test
    fun `preserves urdu text`() {
        val urdu = "کل شام 7 بجے"
        val parsed = MiniJson.parse(MiniJson.write(jsonOf("t" to urdu.json()))) as JsonValue.Obj
        assertEquals(urdu, parsed.string("t"))
    }

    @Test
    fun `accepts a quoted number because models emit them`() {
        val parsed = MiniJson.parse("""{"reminder_minutes":"30"}""") as JsonValue.Obj
        assertEquals(30, parsed.int("reminder_minutes"))
    }

    @Test
    fun `rejects malformed input`() {
        assertThrows(JsonException::class.java) { MiniJson.parse("""{"a":}""") }
        assertThrows(JsonException::class.java) { MiniJson.parse("""{"a":1""") }
        assertThrows(JsonException::class.java) { MiniJson.parse("""{"a":1} trailing""") }
        assertThrows(JsonException::class.java) { MiniJson.parse("""{'a':1}""") }
        assertNull(MiniJson.parseOrNull("not json at all"))
    }

    @Test
    fun `refuses to recurse without a bound`() {
        val deep = "[".repeat(200) + "]".repeat(200)
        assertThrows(JsonException::class.java) { MiniJson.parse(deep) }
    }

    @Test
    fun `pulls an object out of a fenced code block`() {
        val output = """
            Sure! Here is the JSON:
            ```json
            {"intent":"create_task","title":"Gym"}
            ```
            Let me know if you need anything else.
        """.trimIndent()
        val obj = MiniJson.extractFirstObject(output)
        assertNotNull(obj)
        assertEquals("Gym", obj?.string("title"))
    }

    @Test
    fun `does not stop at a brace inside a string`() {
        val output = """Here: {"title":"a } b","intent":"create_task"} done"""
        val obj = MiniJson.extractFirstObject(output)
        assertEquals("a } b", obj?.string("title"))
        assertEquals("create_task", obj?.string("intent"))
    }

    @Test
    fun `returns nothing when there is no object`() {
        assertNull(MiniJson.extractFirstObject("I am not able to help with that."))
        assertNull(MiniJson.extractFirstObject("{ unbalanced"))
    }

    @Test
    fun `writes integers without a decimal point`() {
        assertTrue(MiniJson.write(jsonOf("n" to 30.json())).contains("\"n\":30"))
    }
}
