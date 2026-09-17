package com.personal.assistant.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class AssistantInterpreterTest {

    private val now = LocalDateTime.of(2026, 9, 17, 10, 0)
    private val context = AiContext(now = now, categoryNames = listOf("Development"))
    private val rules = RuleBasedParser(categoryNames = listOf("Development"))

    private class RecordingProvider(
        private val response: ParseResult?,
        private val available: Boolean = true,
        private val throws: Boolean = false,
    ) : AiProvider {
        var interpretCalls = 0
            private set

        override val id = "test"
        override val displayName = "Test model"
        override suspend fun isAvailable() = available
        override suspend fun interpret(input: String, context: AiContext): ParseResult? {
            interpretCalls++
            if (throws) throw IllegalStateException("model crashed")
            return response
        }

        override suspend fun release() = Unit
    }

    @Test
    fun `a confident rule parse never wakes the model`() = runBlocking {
        val provider = RecordingProvider(response = null)
        val interpreter = AssistantInterpreter(rules, provider)
        val result = interpreter.interpret("Tomorrow at 10 AM work on the website", context)
        assertEquals(ParseSource.RULES, result.source)
        assertEquals(0, provider.interpretCalls)
    }

    @Test
    fun `an unclear message asks the model`() = runBlocking {
        val modelDraft = TaskDraft(title = "Team retrospective", date = now.toLocalDate().plusDays(1))
        val provider = RecordingProvider(
            ParseResult(AssistantCommand.CreateTask(modelDraft), ParseSource.MODEL, 0.7f),
        )
        val interpreter = AssistantInterpreter(rules, provider)
        val result = interpreter.interpret("lets do the retro thing", context)
        assertEquals(1, provider.interpretCalls)
        assertEquals(ParseSource.MODEL, result.source)
    }

    @Test
    fun `the rules keep the last word on times`() = runBlocking {
        // The rules read "kal 4 baje" as tomorrow 16:00; the model guessed a different time.
        val modelDraft = TaskDraft(
            title = "Website deployment",
            date = now.toLocalDate(),
            startTime = LocalTime.of(4, 0),
        )
        val provider = RecordingProvider(
            ParseResult(AssistantCommand.CreateTask(modelDraft), ParseSource.MODEL, 0.9f),
        )
        val interpreter = AssistantInterpreter(rules, provider)
        val result = interpreter.interpret("kal 4 baje deployment ka kaam", context)
        val draft = (result.command as AssistantCommand.CreateTask).draft
        assertEquals(ParseSource.HYBRID, result.source)
        assertEquals(LocalTime.of(16, 0), draft.startTime)
        assertEquals(now.toLocalDate().plusDays(1), draft.date)
    }

    @Test
    fun `a crashing model falls back to the rules`() = runBlocking {
        val provider = RecordingProvider(response = null, throws = true)
        val interpreter = AssistantInterpreter(rules, provider)
        val result = interpreter.interpret("something the rules are unsure about", context)
        assertEquals(ParseSource.RULES, result.source)
    }

    @Test
    fun `no model at all still produces a command`() = runBlocking {
        val interpreter = AssistantInterpreter(rules, NoAiProvider)
        val result = interpreter.interpret("Kal 5 baje client ko call karna hai", context)
        assertEquals(ParseSource.RULES, result.source)
        assertTrue(result.command is AssistantCommand.CreateTask)
    }

    @Test
    fun `an unavailable model is not asked twice`() = runBlocking {
        val provider = RecordingProvider(response = null, available = false)
        val interpreter = AssistantInterpreter(rules, provider)
        interpreter.interpret("vague thing", context)
        assertEquals(0, provider.interpretCalls)
    }

    @Test
    fun `a model question does not override a rule answer that acts`() = runBlocking {
        val provider = RecordingProvider(
            ParseResult(
                AssistantCommand.Clarify("What do you mean?", MissingField.TITLE),
                ParseSource.MODEL,
                0.9f,
            ),
        )
        val interpreter = AssistantInterpreter(rules, provider)
        // The rules resolve this to a reschedule with moderate confidence.
        val result = interpreter.interpret("move website to 6", context)
        assertTrue(result.command is AssistantCommand.RescheduleTask)
    }

    @Test
    fun `actionability is classified correctly`() {
        assertTrue(AssistantCommand.DeleteTask(TaskReference(taskId = 1)).isActionable)
        assertFalse(AssistantCommand.SmallTalk("hi").isActionable)
        assertFalse(AssistantCommand.Clarify("when?", MissingField.DATE).isActionable)
    }
}
