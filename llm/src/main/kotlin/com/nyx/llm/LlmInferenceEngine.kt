package com.nyx.llm

/**
 * Backend-agnostic LLM inference interface. Implementations switch between
 * MediaPipe (Gemma 3) and llama.cpp JNI (Llama 3 / Mistral).
 */
interface LlmInferenceEngine {
    /**
     * Generate a response for [prompt] within [maxTokens] tokens.
     * Returns the generated text. Suspends until generation completes.
     */
    suspend fun generate(prompt: String, maxTokens: Int = 512): String

    /**
     * Stream tokens as they are generated.
     * Callers collect the flow for real-time TTS or UI streaming.
     */
    fun stream(prompt: String, maxTokens: Int = 512): kotlinx.coroutines.flow.Flow<String>

    /** Release native resources. Must be called when the engine is no longer needed. */
    fun close()
}
