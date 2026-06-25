package com.nyx.agent.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Central MCP tool registry — the "app store" of the agent's capabilities.
 *
 * Tools are registered at service init with their [ToolDescriptor] (name,
 * description, input schema, risk level). The registry is the single source
 * of truth for capability discovery: [listTools] feeds the LLM system prompt,
 * and [get] resolves a tool name to its descriptor + executor for dispatch.
 */
class McpRegistry {
    private val tools: MutableMap<String, RegisteredTool> = mutableMapOf()

    data class RegisteredTool(val descriptor: ToolDescriptor, val executor: ToolExecutor)

    /** Register (or replace) a tool. */
    fun register(descriptor: ToolDescriptor, executor: ToolExecutor) {
        tools[descriptor.name] = RegisteredTool(descriptor, executor)
    }

    /** Unregister a tool by name. */
    fun unregister(name: String) { tools.remove(name) }

    /** Look up a registered tool by name. */
    fun get(name: String): RegisteredTool? = tools[name]

    /** All registered tool descriptors. */
    fun listTools(): List<ToolDescriptor> = tools.values.map { it.descriptor }.sortedBy { it.name }

    /** Whether a tool is registered. */
    fun contains(name: String): Boolean = name in tools
}

/**
 * Serializes the tool catalog to MCP protocol format for injection into the
 * LLM system prompt. The LLM parses this listing and emits tool-call JSON
 * that the Intent Bridge parses back into [ToolCall]s.
 */
object McpCapabilityAdvertiser {
    fun serialize(tools: List<ToolDescriptor>): String {
        val items = tools.joinToString(",\n") { t ->
            val params = t.params.joinToString(",\n") { p ->
                val enumPart = p.`enum`?.let { """, "enum":["${it.joinToString("\",\"")}"]""" } ?: ""
                """{"name":"${p.name}","type":"${p.type}","description":"${p.description}","required":${p.required}$enumPart}"""
            }
            """{"name":"${t.name}","description":"${t.description}","risk":"${t.risk}","namespace":"${t.namespace}","params":[$params]}"""
        }
        return """{"tools":[$items]}"""
    }
}

/**
 * Optional cloud tool bridge. Exposes cloud-hosted tools (web search, CRM
 * lookup, email) over HTTPS to the on-device MCP client. Only TLS 1.3 is
 * accepted; invalid certificates are refused (no TrustAllCerts workarounds).
 */
interface CloudToolBridge {
    /** Whether the bridge is connected over a trusted TLS 1.3 session. */
    fun isConnected(): Boolean
    /** Execute a cloud tool call. */
    suspend fun execute(call: ToolCall): ToolResult
}
