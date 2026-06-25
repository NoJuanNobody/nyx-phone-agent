package com.nyx.llm.backends

import com.nyx.llm.LlmInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * JNI bridge to llama.cpp for Llama 3 / Mistral on-device inference.
 *
 * Loads the native library [nyx_llm_jni] and delegates generation to the
 * C++ implementation via [llamaGenerate]. The model file must be present at
 * [modelPath] on the device filesystem (e.g. in the app's files directory).
 *
 * Build requirement: CMakeLists.txt compiles llama_jni.cpp into libnyx_llm_jni.so.
 */
class LlamaCppBackend(private val modelPath: String) : LlmInferenceEngine {

    init {
        System.loadLibrary("nyx_llm_jni")
    }

    /**
     * Native function implemented in llama_jni.cpp.
     * Generates text using llama.cpp for the given [prompt] up to [maxTokens] tokens.
     */
    private external fun llamaGenerate(prompt: String, maxTokens: Int, modelPath: String): String

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        return llamaGenerate(prompt, maxTokens, modelPath)
    }

    override fun stream(prompt: String, maxTokens: Int): Flow<String> = flow {
        emit(generate(prompt, maxTokens))
    }

    override fun close() {
        // TODO(prod): release llama.cpp context / model memory via JNI
    }
}
