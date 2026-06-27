package com.nyx.llm.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/** One message in a chat conversation, OpenAI/OpenRouter-compatible. */
data class ChatMessage(
    val role: String,                       // "system" | "user" | "assistant" | "tool"
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),  // assistant turns that call tools
    val toolCallId: String? = null,         // for role == "tool": which call this answers
    val name: String? = null,               // for role == "tool": the tool name
)

/** A tool/function the model asked to invoke. [argumentsJson] is a raw JSON object string. */
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** A tool the model may call. [parameters] is a JSON-Schema object describing the args. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** Model output for one turn: either prose [content], or [toolCalls] to run (possibly both). */
data class ChatResult(val content: String?, val toolCalls: List<ToolCall>)

/**
 * Minimal OpenRouter chat-completions client with tool-calling support (OpenAI-compatible).
 * Interim cloud backend; a local model server later can implement the same call shape.
 */
class OpenRouterChatClient(
    private val apiKey: String,
    private val endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun chat(
        model: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        maxTokens: Int = 600,
    ): ChatResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0)
            put("messages", buildJsonArray { messages.forEach { add(encodeMessage(it)) } })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            }
                        }
                    }
                })
            }
        }.toString()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("HTTP-Referer", "https://github.com/nyx-phone-agent")
            setRequestProperty("X-Title", "Nyx Phone Agent")
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("OpenRouter HTTP $code: $text")
            parseResult(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun encodeMessage(m: ChatMessage): JsonObject = buildJsonObject {
        put("role", m.role)
        // content may be null on assistant tool-call turns; OpenRouter accepts null/empty.
        put("content", m.content ?: "")
        m.toolCallId?.let { put("tool_call_id", it) }
        m.name?.let { put("name", it) }
        if (m.toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                m.toolCalls.forEach { tc ->
                    addJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tc.name)
                            put("arguments", tc.argumentsJson)
                        }
                    }
                }
            })
        }
    }

    private fun parseResult(responseText: String): ChatResult {
        val message = json.parseToJsonElement(responseText).jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: error("OpenRouter: no message in response: $responseText")

        val content = message["content"]?.jsonPrimitive?.contentOrNullSafe()
        val toolCalls = message["tool_calls"]?.jsonArray.orEmpty().map { el ->
            val o = el.jsonObject
            val fn = o["function"]!!.jsonObject
            ToolCall(
                id = o["id"]?.jsonPrimitive?.content ?: "",
                name = fn["name"]?.jsonPrimitive?.content ?: "",
                argumentsJson = fn["arguments"]?.jsonPrimitive?.content ?: "{}",
            )
        }
        return ChatResult(content, toolCalls)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}
