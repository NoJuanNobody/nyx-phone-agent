package com.nyx.agent.intent

import com.nyx.agent.mcp.JsonSchemaValidator
import com.nyx.agent.mcp.McpRegistry
import com.nyx.agent.mcp.ToolCall

/**
 * Validates a parsed [ToolCall] against the registered tool's JSON schema.
 *
 * On failure, [errorMessage] produces a structured error feedback string
 * suitable for injection into the LLM context so the model can produce a
 * corrected tool call on the next turn.
 */
class IntentValidator(private val registry: McpRegistry) {

    /**
     * Validate [call]. Returns null on success, or a feedback string.
     */
    fun validate(call: ToolCall): String? {
        val registered = registry.get(call.name)
            ?: return "Tool '${call.name}' is not registered. Available tools: ${registry.listTools().map { it.name }}"
        return JsonSchemaValidator.validate(registered.descriptor, call.arguments)
    }
}

/**
 * When multiple contacts match an entity, generate a disambiguation question
 * to speak via TTS within the same call turn.
 */
object AmbiguityResolver {
    fun disambiguationQuestion(resolution: ContactResolution.Ambiguous): String {
        val names = resolution.matches.joinToString(", ") { it.name }
        return "I found multiple contacts: $names. Which one do you mean?"
    }
}

/**
 * Retry policy for transient failures (malformed LLM output).
 *
 * Retries up to [maxRetries] times; each retry attempt is logged to the
 * ACP audit logger. After exhausting retries, escalates to human.
 */
class IntentRetryPolicy(val maxRetries: Int = 3) {
    data class Attempt(val number: Int, val ok: Boolean, val error: String?)

    /**
     * Run [block] up to [maxRetries] times, retrying when [block] returns a
     * non-null error. Returns the final attempt.
     */
    fun <T> runWithRetry(onAttempt: (Int, String?) -> Unit, block: (Int) -> Result<T>): Result<T> {
        var lastError: String? = null
        for (attempt in 1..maxRetries) {
            onAttempt(attempt, lastError)
            val r = block(attempt)
            if (r.isSuccess) return r
            lastError = r.exceptionOrNull()?.message
        }
        return Result.failure(IllegalStateException("intent retries exhausted: $lastError; escalating to human"))
    }
}
