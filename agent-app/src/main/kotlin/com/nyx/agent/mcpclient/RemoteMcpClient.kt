package com.nyx.agent.mcpclient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/** A tool advertised by a remote MCP server. */
data class McpRemoteTool(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * Minimal client for a remote MCP server over the **Streamable HTTP** transport
 * (JSON-RPC 2.0 POSTed to a single endpoint; responses as JSON or SSE). stdio
 * transport is intentionally unsupported — a phone can't spawn server subprocesses.
 *
 * Lifecycle: [connect] runs `initialize` + `notifications/initialized` + `tools/list`
 * and returns the advertised tools; [callTool] invokes one. Not thread-safe; drive
 * from a single coroutine.
 */
class RemoteMcpClient(
    private val endpoint: String,
    private val authToken: String? = null,
    private val protocolVersion: String = "2024-11-05",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionId: String? = null
    private var nextId = 1

    suspend fun connect(): List<McpRemoteTool> {
        rpc("initialize", buildJsonObject {
            put("protocolVersion", protocolVersion)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "nyx"); put("version", "0.1.0") }
        }, captureSession = true)
        notify("notifications/initialized")

        val result = rpc("tools/list", buildJsonObject {})
        val tools = result["tools"]?.jsonArray ?: return emptyList()
        return tools.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpRemoteTool(
                name = name,
                description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
                inputSchema = (o["inputSchema"] as? JsonObject) ?: EMPTY_SCHEMA,
            )
        }
    }

    /** Calls [name] with [arguments]; returns the tool's text output (or a JSON-RPC error string). */
    suspend fun callTool(name: String, arguments: JsonObject): String {
        val result = rpc("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        val content = result["content"]?.jsonArray
            ?: return result.toString()
        return content.joinToString("\n") { item ->
            val o = item.jsonObject
            o["text"]?.jsonPrimitive?.contentOrNull ?: o.toString()
        }.ifBlank { "ok" }
    }

    // -- JSON-RPC over Streamable HTTP --------------------------------------

    private suspend fun rpc(method: String, params: JsonObject, captureSession: Boolean = false): JsonObject {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        val (responseText, sessionHeader) = post(body, captureSession)
        if (captureSession && sessionHeader != null) sessionId = sessionHeader

        val message = parseRpcMessage(responseText, id)
            ?: error("MCP $method: no JSON-RPC response in: ${responseText.take(300)}")
        (message["error"] as? JsonObject)?.let { err ->
            val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
            error("MCP $method error: $msg")
        }
        return (message["result"] as? JsonObject) ?: JsonObject(emptyMap())
    }

    private suspend fun notify(method: String) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", buildJsonObject {})
        }.toString()
        runCatching { post(body, captureSession = false) }  // notifications return 202/empty; ignore body
    }

    private suspend fun post(body: String, captureSession: Boolean): Pair<String, String?> =
        withContext(Dispatchers.IO) {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
                setRequestProperty("MCP-Protocol-Version", protocolVersion)
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("MCP HTTP $code: ${text.take(300)}")
                val session = if (captureSession) {
                    conn.headerFields.entries
                        .firstOrNull { it.key?.equals("Mcp-Session-Id", ignoreCase = true) == true }
                        ?.value?.firstOrNull()
                } else null
                text to session
            } finally {
                conn.disconnect()
            }
        }

    /** Handles both a plain JSON-RPC body and an SSE stream; returns the message matching [id]. */
    private fun parseRpcMessage(responseText: String, id: Int): JsonObject? {
        val candidates = if (responseText.contains("data:")) {
            responseText.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .toList()
        } else {
            listOf(responseText.trim())
        }
        var fallback: JsonObject? = null
        for (chunk in candidates) {
            val obj = runCatching { json.parseToJsonElement(chunk).jsonObject }.getOrNull() ?: continue
            if ((obj["id"]?.jsonPrimitive?.contentOrNull) == id.toString()) return obj
            if (obj.containsKey("result") || obj.containsKey("error")) fallback = obj
        }
        return fallback
    }

    private companion object {
        val EMPTY_SCHEMA = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
    }
}
