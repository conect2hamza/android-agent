package com.personal.assistant.ai

import android.util.Log
import com.personal.assistant.core.ai.AiContext
import com.personal.assistant.core.ai.AiProvider
import com.personal.assistant.core.ai.CommandCodec
import com.personal.assistant.core.ai.ParseResult
import com.personal.assistant.core.ai.ParseSource
import com.personal.assistant.core.ai.PromptBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs an installed local model, and gets out of the way when there isn't one.
 *
 * Three properties matter more here than raw quality:
 *
 *  - **The model is not resident.** It is loaded on demand and released after [idleTimeoutMillis] of
 *    quiet, and again whenever the app goes to the background. The specification is explicit that a
 *    language model must not sit running in the background, and that is the single largest factor in
 *    the app's battery and memory footprint.
 *  - **Generation is bounded.** A phone-sized model can ramble; a timeout plus a token cap means a bad
 *    generation costs a second, not a frozen chat.
 *  - **Failure is ordinary.** Every failure path returns null, which sends the caller to the
 *    deterministic parser. Nothing here can make the app unusable.
 */
class LocalLlmProvider(
    private val runtime: LlmRuntime,
    private val modelStore: ModelStore,
    private val modelId: String,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val generationTimeoutMillis: Long = DEFAULT_GENERATION_TIMEOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AiProvider {

    private val lock = Mutex()
    private var lastUsedAt: Long = 0

    override val id: String get() = modelId

    override val displayName: String
        get() = modelStore.find(modelId)?.displayName ?: modelId

    override suspend fun isAvailable(): Boolean {
        val model = modelStore.find(modelId) ?: return false
        return modelStore.canHost(model.sizeBytes)
    }

    override suspend fun interpret(input: String, context: AiContext): ParseResult? {
        val model = modelStore.find(modelId) ?: return null
        if (!modelStore.canHost(model.sizeBytes)) {
            Log.i(TAG, "Skipping model: not enough memory headroom on this device")
            return null
        }

        return lock.withLock {
            if (!runtime.isLoaded && !runtime.load(model.file.absolutePath)) {
                Log.w(TAG, "Model failed to load; falling back to the rule-based parser")
                return@withLock null
            }
            lastUsedAt = clock()

            val prompt = buildString {
                append(PromptBuilder.system(context))
                appendLine()
                append("User: ")
                append(PromptBuilder.user(input))
                appendLine()
            }

            val raw = withTimeoutOrNull(generationTimeoutMillis) {
                runtime.generate(prompt, maxTokens = MAX_TOKENS)
            }
            if (raw.isNullOrBlank()) {
                Log.w(TAG, "Model produced nothing within ${generationTimeoutMillis}ms")
                return@withLock null
            }

            val command = CommandCodec.decode(raw, context) ?: return@withLock null
            ParseResult(command, ParseSource.MODEL, DEFAULT_CONFIDENCE)
        }
    }

    override suspend fun release() {
        lock.withLock {
            if (runtime.isLoaded) runtime.unload()
        }
    }

    /** Called from a lifecycle observer; releases the weights once the model has gone quiet. */
    suspend fun releaseIfIdle() {
        if (!runtime.isLoaded) return
        if (clock() - lastUsedAt < idleTimeoutMillis) return
        release()
    }

    companion object {
        private const val TAG = "LocalLlmProvider"
        private const val MAX_TOKENS = 256

        /**
         * Model output is never trusted outright, so its confidence sits below the threshold at which
         * the assistant acts without showing a confirmation card.
         */
        private const val DEFAULT_CONFIDENCE = 0.7f

        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 90_000L
        const val DEFAULT_GENERATION_TIMEOUT_MILLIS = 12_000L
    }
}
