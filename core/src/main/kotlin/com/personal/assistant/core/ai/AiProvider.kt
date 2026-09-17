package com.personal.assistant.core.ai

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * What the language layer is told about the user's situation before it interprets a message.
 *
 * It is deliberately thin. Only memories the user asked to keep, only the categories that exist, only
 * enough recent titles to resolve "move my website task" -- never the full task list or chat history.
 * A 0.6B model gains nothing from a larger prompt and the user gains nothing from more of their data
 * being funnelled through it.
 */
data class AiContext(
    val now: LocalDateTime,
    val categoryNames: List<String> = emptyList(),
    val memories: List<String> = emptyList(),
    val recentTaskTitles: List<String> = emptyList(),
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    /** Set when the previous turn asked a question, so a bare "5 PM" can complete that request. */
    val pendingClarification: AssistantCommand.Clarify? = null,
)

/**
 * A replaceable language backend.
 *
 * The specification requires the model to be swappable without touching the rest of the app, and this
 * interface is the seam: a provider receives text and returns a [ParseResult], or null if it could not
 * produce one. Returning null is a normal outcome -- no model installed, not enough memory on the
 * device, output that failed validation -- and the caller falls back to the deterministic parser.
 */
interface AiProvider {

    /** Stable identifier shown in settings and stored with the user's model choice. */
    val id: String

    val displayName: String

    /** False when the weights are missing or the device cannot host the model. */
    suspend fun isAvailable(): Boolean

    suspend fun interpret(input: String, context: AiContext): ParseResult?

    /**
     * Frees the weights. Called after an idle timeout and whenever the app goes to the background,
     * because the specification forbids keeping the model resident.
     */
    suspend fun release()
}

/** A provider that is always absent. The default, so a fresh install works with no model at all. */
object NoAiProvider : AiProvider {
    override val id: String = "none"
    override val displayName: String = "Rules only (no model)"
    override suspend fun isAvailable(): Boolean = false
    override suspend fun interpret(input: String, context: AiContext): ParseResult? = null
    override suspend fun release() = Unit
}
