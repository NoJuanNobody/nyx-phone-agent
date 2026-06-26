package com.nyx.agent.intent

import com.nyx.agent.mcp.McpRegistry
import com.nyx.agent.mcp.ToolCall
import kotlinx.serialization.json.put

/**
 * The Intent Bridge — the translator between raw LLM output and structured
 * tool calls.
 *
 * Pipeline: LLM output → parse → validate → entity resolve → dispatch.
 *
 * The bridge is intentionally synchronous and lightweight for non-ambiguous
 * intents (< 200ms). Disambiguation (ambiguous contacts) is surfaced to the
 * caller to resolve via TTS in the same call turn.
 */
class IntentBridge(
    private val registry: McpRegistry,
    private val resolver: EntityResolver = EntityResolver()
) {
    sealed class Outcome {
        data class Ready(val call: ToolCall) : Outcome()
        data class Disambiguate(val question: String, val partial: ToolCall) : Outcome()
        data class Error(val message: String) : Outcome()
    }

    /**
     * Process raw LLM output into a ready-to-dispatch tool call (or a
     * disambiguation request / error).
     */
    fun process(rawLlmOutput: String): Outcome {
        val parsed = LlmOutputParser.extractFirstToolCall(rawLlmOutput)
            ?: return Outcome.Error("No tool call found in LLM output")
        val validator = IntentValidator(registry)
        val err = validator.validate(parsed)
        if (err != null) return Outcome.Error(err)
        // Entity resolution: if a contact name is present and ambiguous, ask.
        parsed.arguments["contact"]?.let { contactEl ->
            val name = contactEl.toString().trim('"')
            when (val r = resolver.resolveContact(name)) {
                is ContactResolution.Ambiguous -> {
                    val q = AmbiguityResolver.disambiguationQuestion(r)
                    return Outcome.Disambiguate(q, parsed)
                }
                is ContactResolution.Resolved -> {
                    val merged = kotlinx.serialization.json.buildJsonObject {
                        parsed.arguments.forEach { (k, v) -> put(k, v) }
                        put("to", r.number)
                    }
                    return Outcome.Ready(ToolCall(parsed.name, merged))
                }
                is ContactResolution.NotFound -> return Outcome.Error("Contact '$name' not found")
            }
        }
        return Outcome.Ready(parsed)
    }
}
