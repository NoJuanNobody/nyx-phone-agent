package com.nyx.llm.backends

import com.nyx.llm.LlmInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Google MediaPipe LLM Inference API backend for Gemma 3 (2B / 4B).
 *
 * Requires the MediaPipe Tasks GenAI library and a Gemma 3 model asset in assets/.
 * Model path defaults to "gemma3-2b-it-q4.task".
 *
 * NOTE: MediaPipe LlmInference is not available as a direct compile dep in this scaffold.
 * The production implementation requires: implementation("com.google.mediapipe:tasks-genai:0.10.14")
 * Use the abstract bridge pattern; stub returns "[MediaPipe stub]" until the dep is wired.
 */
class MediaPipeLlmBackend(private val modelAssetPath: String = "gemma3-2b-it-q4.task") : LlmInferenceEngine {
    // In production: initialize MediaPipe LlmInference session here
    override suspend fun generate(prompt: String, maxTokens: Int): String {
        // TODO(prod): replace with actual MediaPipe LlmInference.generateAsync call
        return "[MediaPipe stub: $prompt]"
    }

    override fun stream(prompt: String, maxTokens: Int): Flow<String> = flow {
        emit(generate(prompt, maxTokens))
    }

    override fun close() { /* release MediaPipe session */ }
}
