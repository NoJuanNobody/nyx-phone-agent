package com.nyx.agent.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Risk level declared by a tool. Used by the Policy Engine to decide
 * whether confirmation is required before execution.
 */
@Serializable
enum class ToolRisk { LOW, MEDIUM, HIGH }

/**
 * JSON Schema fragment describing a single tool parameter.
 */
@Serializable
data class ToolParam(
    val name: String,
    val type: String,          // "string" | "number" | "boolean" | "object" | "array"
    val description: String,
    val required: Boolean = true,
    val `enum`: List<String>? = null
)

/**
 * Metadata describing a registered MCP tool.
 *
 * @property name Unique tool name (e.g. "telephony.answer").
 * @property description Human-readable description injected into LLM prompt.
 * @property params Input parameter schemas.
 * @property risk Risk level for policy gating.
 * @property namespace ACP namespace the tool routes to.
 */
@Serializable
data class ToolDescriptor(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("params") val params: List<ToolParam> = emptyList(),
    @SerialName("risk") val risk: ToolRisk = ToolRisk.LOW,
    @SerialName("namespace") val namespace: String
)

/**
 * A parsed tool-call request produced by the LLM and validated against a
 * [ToolDescriptor].
 */
@Serializable
data class ToolCall(
    val name: String,
    val arguments: JsonObject
)

/**
 * Result of executing a tool call.
 */
@Serializable
data class ToolResult(
    val ok: Boolean,
    val output: JsonObject,
    val error: String? = null
)
