package com.nyx.llm

/**
 * Constructs the system prompt injected at the start of every LLM context.
 * Includes: persona description, tool catalog (MCP), active policy constraints, timestamp.
 */
class SystemPromptBuilder {
    var persona: String = "You are Nyx, an on-device AI phone agent."
    var toolCatalog: List<String> = emptyList()
    var policyConstraints: List<String> = emptyList()

    fun build(timestamp: String = ""): String {
        val sb = StringBuilder()
        sb.appendLine("SYSTEM: $persona")
        if (timestamp.isNotBlank()) sb.appendLine("Current time: $timestamp")
        if (toolCatalog.isNotEmpty()) {
            sb.appendLine("Available tools:")
            toolCatalog.forEach { sb.appendLine("  - $it") }
        }
        if (policyConstraints.isNotEmpty()) {
            sb.appendLine("Active constraints:")
            policyConstraints.forEach { sb.appendLine("  - $it") }
        }
        return sb.toString().trimEnd()
    }
}
