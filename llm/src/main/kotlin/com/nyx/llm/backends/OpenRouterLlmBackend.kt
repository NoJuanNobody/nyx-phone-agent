package com.nyx.llm.backends

import com.nyx.llm.LlmInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * [LlmInferenceEngine] backed by OpenRouter (https://openrouter.ai), an OpenAI-compatible
 * chat-completions gateway. This is the interim cloud backend; the long-term target is a
 * local model on the user's own machine behind this same interface.
 *
 * Uses [HttpURLConnection] directly to avoid pulling an HTTP-client dependency into the
 * scaffold. Network work runs on [Dispatchers.IO].
 *
 * @param apiKey OpenRouter API key (`sk-or-...`).
 * @param model OpenRouter model id, e.g. `"openai/gpt-4o-mini"`. Chosen on-device by the user.
 * @param systemPrompt optional system message prepended to every request.
 */
class OpenRouterLlmBackend(
    private val apiKey: String,
    private val model: String = "openai/gpt-4o-mini",
    private val systemPrompt: String? = null,
    private val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
) : LlmInferenceEngine {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(prompt: String, maxTokens: Int): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0)
            put("messages", buildJsonArray {
                systemPrompt?.let { sys ->
                    add(buildJsonObject { put("role", "system"); put("content", sys) })
                }
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            })
        }.toString()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            // OpenRouter attribution headers (optional but recommended).
            setRequestProperty("HTTP-Referer", "https://github.com/nyx-phone-agent")
            setRequestProperty("X-Title", "Nyx Phone Agent")
        }

        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("OpenRouter HTTP $code: $responseText")
            }
            extractContent(responseText)
        } finally {
            conn.disconnect()
        }
    }

    override fun stream(prompt: String, maxTokens: Int): Flow<String> = flow {
        emit(generate(prompt, maxTokens))
    }

    override fun close() { /* stateless */ }

    private fun extractContent(responseText: String): String {
        val root = json.parseToJsonElement(responseText).jsonObject
        val choices = root["choices"]?.jsonArray
            ?: error("OpenRouter: response had no 'choices': $responseText")
        val first = choices.firstOrNull()?.jsonObject
            ?: error("OpenRouter: empty 'choices': $responseText")
        return (first["message"] as? JsonObject)?.get("content")?.jsonPrimitive?.content
            ?: error("OpenRouter: no message content: $responseText")
    }
}
