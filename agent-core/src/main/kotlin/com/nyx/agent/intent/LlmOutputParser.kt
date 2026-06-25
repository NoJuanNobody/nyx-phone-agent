package com.nyx.agent.intent

import com.nyx.agent.mcp.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses LLM streaming output to detect and extract function-call JSON
 * blocks embedded in conversational text.
 *
 * The LLM may emit mixed text/JSON, partial JSON (streaming), and multi-call
 * outputs. This parser tolerates all of those: it scans for the first
 * balanced `{...}` JSON object that looks like a tool call (has a `name` or
 * `tool` field and an `arguments`/`parameters` field), and returns it.
 *
 * On malformed JSON it returns null so the retry policy can re-prompt.
 */
object LlmOutputParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Extract the first tool-call JSON object from [raw] LLM output.
     * Returns null if none is found or the JSON is unparseable.
     */
    fun extractFirstToolCall(raw: String): ToolCall? = extractToolCalls(raw).firstOrNull()

    /**
     * Extract all tool-call JSON objects from [raw] output.
     */
    fun extractToolCalls(raw: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()
        var idx = 0
        while (idx < raw.length) {
            val start = raw.indexOf('{', idx)
            if (start < 0) break
            val end = findBalancedBrace(raw, start)
            if (end < 0) break // partial JSON; stop
            val candidate = raw.substring(start, end + 1)
            parseToolCall(candidate)?.let { results.add(it) }
            idx = end + 1
        }
        return results
    }

    private fun parseToolCall(jsonStr: String): ToolCall? = try {
        val obj = json.parseToJsonElement(jsonStr) as? JsonObject ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
            ?: obj["tool"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val argsEl = obj["arguments"] ?: obj["parameters"] ?: return null
        val args = (argsEl as? JsonObject)
            ?: (argsEl as? JsonPrimitive)?.let { p ->
                json.parseToJsonElement(p.contentOrNull ?: return null) as? JsonObject
            }
            ?: return null
        ToolCall(name, args)
    } catch (_: Throwable) { null }

    /** Find the index of the brace that closes the object starting at [start]. */
    private fun findBalancedBrace(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escape -> { escape = false }
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1 // unbalanced
    }
}
