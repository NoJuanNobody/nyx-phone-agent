package com.nyx.agent.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Executes a single tool call. Implementations route to ACP for OS-level
 * actions or to cloud bridges for remote tools.
 */
fun interface ToolExecutor {
    suspend fun execute(call: ToolCall): ToolResult
}

/**
 * Coroutine-based sandbox for tool execution.
 *
 * Each tool call runs in an isolated coroutine scope with:
 *  - a configurable timeout (default 10s); long-running calls are cancelled
 *    and return a structured timeout error (never hang the LLM loop);
 *  - exception isolation — any thrown exception is caught and returned as a
 *    structured tool error.
 */
class McpToolSandbox(private val timeoutMs: Long = 10_000L) {
    suspend fun run(call: ToolCall, executor: ToolExecutor): ToolResult =
        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { executor.execute(call) }
                ?: ToolResult(false, JsonObject(emptyMap()), "tool '${call.name}' timed out after ${timeoutMs}ms")
        } catch (t: Throwable) {
            ToolResult(false, JsonObject(emptyMap()), "tool '${call.name}' failed: ${t.message}")
        }
}
