package com.nyx.llm

/**
 * Enforces a per-turn output token budget to prevent runaway generation.
 * Wraps an [LlmInferenceEngine] and truncates output at [maxOutputTokens] tokens.
 */
class LlmTokenBudgetEnforcer(
    private val engine: LlmInferenceEngine,
    private val maxOutputTokens: Int = 256,
) : LlmInferenceEngine {
    override suspend fun generate(prompt: String, maxTokens: Int): String {
        val capped = minOf(maxTokens, maxOutputTokens)
        return engine.generate(prompt, capped)
    }

    override fun stream(prompt: String, maxTokens: Int) =
        engine.stream(prompt, minOf(maxTokens, maxOutputTokens))

    override fun close() = engine.close()
}
