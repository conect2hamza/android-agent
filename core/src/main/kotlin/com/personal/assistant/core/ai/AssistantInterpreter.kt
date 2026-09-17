package com.personal.assistant.core.ai

/**
 * Decides how a message is understood: deterministic rules first, the local model only where rules
 * fall short, and the rules again if the model produces nothing usable.
 *
 * The specification asks for a model-driven assistant that still works with no model at all. Running
 * the rules unconditionally is what makes both true at once, and it also keeps the common cases off
 * the model entirely -- which is the single biggest saving in battery and latency, far larger than any
 * choice of quantisation.
 */
class AssistantInterpreter(
    private val rules: RuleBasedParser,
    private val provider: AiProvider = NoAiProvider,
    /** Above this, the rules are trusted outright and the model is never woken. */
    private val ruleConfidenceFloor: Float = 0.8f,
) {

    suspend fun interpret(input: String, context: AiContext): ParseResult {
        val ruleResult = rules.parse(input, context.now)

        if (ruleResult.confidence >= ruleConfidenceFloor && ruleResult.command.isActionable) {
            return ruleResult
        }
        if (!isModelWorthWaking(ruleResult)) return ruleResult

        val available = runCatching { provider.isAvailable() }.getOrDefault(false)
        if (!available) return ruleResult

        val modelResult = runCatching { provider.interpret(input, context) }.getOrNull() ?: return ruleResult
        return merge(ruleResult, modelResult)
    }

    /**
     * The model adds nothing to some outcomes. An empty message is still empty, and a command the
     * rules already resolved into a concrete change does not need a second opinion that could only
     * make it worse.
     */
    private fun isModelWorthWaking(ruleResult: ParseResult): Boolean = when (ruleResult.command) {
        is AssistantCommand.Unsupported -> false
        is AssistantCommand.RememberFact -> false
        else -> true
    }

    /**
     * Combines the two readings. The model decides *what the sentence is asking for*; the rules keep
     * the last word on *when*, because dates and times are arithmetic and a small model is measurably
     * worse at arithmetic than a resolver.
     */
    private fun merge(ruleResult: ParseResult, modelResult: ParseResult): ParseResult {
        val ruleCommand = ruleResult.command
        val modelCommand = modelResult.command

        if (ruleCommand is AssistantCommand.CreateTask && modelCommand is AssistantCommand.CreateTask) {
            val rulesDraft = ruleCommand.draft
            val modelDraft = modelCommand.draft
            val merged = modelDraft.copy(
                // A title the rules extracted from the user's own words beats a paraphrase, but a
                // model title is better than nothing.
                title = rulesDraft.title.ifBlank { modelDraft.title },
                date = rulesDraft.date ?: modelDraft.date,
                startTime = rulesDraft.startTime ?: modelDraft.startTime,
                endTime = rulesDraft.endTime ?: modelDraft.endTime,
                durationMinutes = rulesDraft.durationMinutes ?: modelDraft.durationMinutes,
                recurrence = rulesDraft.recurrence ?: modelDraft.recurrence,
                reminderMinutes = rulesDraft.reminderMinutes ?: modelDraft.reminderMinutes,
                meridiemAssumed = rulesDraft.meridiemAssumed || modelDraft.meridiemAssumed,
                dateDirectionAssumed = rulesDraft.dateDirectionAssumed || modelDraft.dateDirectionAssumed,
            )
            return ParseResult(
                AssistantCommand.CreateTask(merged),
                ParseSource.HYBRID,
                maxOf(ruleResult.confidence, modelResult.confidence),
            )
        }

        // A model answer that only asks a question is worse than a rule answer that acts.
        if (modelCommand is AssistantCommand.Clarify && ruleCommand.isActionable) return ruleResult

        return modelResult
    }
}

/** True for commands that change or read state, as opposed to asking the user for more. */
val AssistantCommand.isActionable: Boolean
    get() = when (this) {
        is AssistantCommand.CreateTask,
        is AssistantCommand.RescheduleTask,
        is AssistantCommand.ChangeStatus,
        is AssistantCommand.SetProgress,
        is AssistantCommand.DeleteTask,
        is AssistantCommand.RememberFact,
        is AssistantCommand.Ask,
        -> true

        is AssistantCommand.Clarify,
        is AssistantCommand.SmallTalk,
        is AssistantCommand.Unsupported,
        -> false
    }
