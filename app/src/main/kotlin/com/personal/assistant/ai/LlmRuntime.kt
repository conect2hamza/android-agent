package com.personal.assistant.ai

/**
 * The inference engine, behind an interface.
 *
 * No engine is bundled in version 1.0, and that is a deliberate choice rather than an omission. The
 * specification requires the model to be selected only after benchmarking RAM, latency, battery and
 * Urdu quality on real devices, and requires every third-party component's licence to be reviewed
 * before shipping. Bundling a runtime and a set of weights now would pre-empt both. What ships instead
 * is this seam plus a deterministic parser good enough to run the product on its own, so adding an
 * engine later is a new implementation of this interface and a settings entry -- not a rewrite.
 *
 * Implementations must tolerate being unloaded at any time: the app releases the model aggressively to
 * keep it from sitting in RAM between messages.
 */
interface LlmRuntime {

    val isLoaded: Boolean

    /** Returns false when the device cannot host the model; never throws for an expected failure. */
    suspend fun load(modelPath: String): Boolean

    /**
     * @param stopAtJsonEnd lets an implementation stop as soon as the JSON object closes, which on a
     *   phone is the difference between a fast reply and one the user waits through.
     */
    suspend fun generate(
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.2f,
        stopAtJsonEnd: Boolean = true,
    ): String?

    suspend fun unload()
}

/** Used when no engine is installed. Every call reports absence rather than failing. */
object NoLlmRuntime : LlmRuntime {
    override val isLoaded: Boolean = false
    override suspend fun load(modelPath: String): Boolean = false
    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        stopAtJsonEnd: Boolean,
    ): String? = null

    override suspend fun unload() = Unit
}
