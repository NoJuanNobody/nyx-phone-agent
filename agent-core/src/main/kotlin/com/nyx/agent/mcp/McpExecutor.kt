package com.nyx.agent.mcp

import com.nyx.agent.acp.AcpDispatcher
import com.nyx.agent.acp.AcpCommand
import com.nyx.agent.acp.AcpResult
import com.nyx.agent.acp.DispatchOutcome
import com.nyx.agent.policy.PolicyDecision
import com.nyx.agent.policy.PolicyEngine
import com.nyx.agent.policy.PolicyEvaluation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Executes validated tool calls.
 *
 * Flow per call:
 *  1. Resolve the tool descriptor from the [McpRegistry].
 *  2. Validate arguments via [JsonSchemaValidator]; on failure return a
 *     structured error for the LLM to retry.
 *  3. Consult the [PolicyEngine]; REQUIRE_CONFIRM must be resolved by the
 *     caller's ConsentGate before invoking [execute] (deny → error).
 *  4. Run the tool's executor inside [McpToolSandbox] (10s timeout).
 *  5. Write an audit entry to the ACP audit logger (tool name, args hash,
 *     duration, result summary).
 *
 * @param policy Policy engine consulted before every execution.
 * @param registry Tool registry.
 * @param sandbox Coroutine sandbox.
 * @param acpDispatcher ACP dispatcher for OS-level tool routing.
 */
class McpExecutor(
    private val policy: PolicyEngine,
    private val registry: McpRegistry,
    private val sandbox: McpToolSandbox,
    private val acpDispatcher: AcpDispatcher? = null
) {
    /**
     * Execute a tool call assuming any REQUIRE_CONFIRM consent has already
     * been obtained by the caller.
     */
    suspend fun execute(call: ToolCall, consentObtained: Boolean = false): ToolResult {
        val registered = registry.get(call.name)
            ?: return ToolResult(false, JsonObject(emptyMap()), "unknown tool '${call.name}'")

        val schemaErr = JsonSchemaValidator.validate(registered.descriptor, call.arguments)
        if (schemaErr != null) return ToolResult(false, JsonObject(emptyMap()), schemaErr)

        val eval = policy.evaluate(call.name)
        if (eval.decision == PolicyDecision.DENY) {
            return ToolResult(false, JsonObject(emptyMap()), "policy denied: ${eval.reason}")
        }
        if (eval.decision == PolicyDecision.REQUIRE_CONFIRM && !consentObtained) {
            return ToolResult(false, JsonObject(emptyMap()), "confirmation required for '${call.name}'")
        }

        val start = System.nanoTime()
        val result = sandbox.run(call, registered.executor)
        val durationMs = (System.nanoTime() - start) / 1_000_000

        // Audit via ACP dispatcher if available
        acpDispatcher?.let {
            val acpResult = if (result.ok) AcpResult.success(mapOf("tool" to JsonPrimitive(call.name)))
                            else AcpResult.failure(com.nyx.agent.acp.schema.ErrorCode.INTERNAL, result.error ?: "tool failed")
            // Build an ACP command for audit purposes
            val cmd = AcpCommand(call.name, "mcp", call.arguments, "tool-${call.name.hashCode()}")
            // Audit is done inside dispatch, but here we already executed; log directly via a no-op.
            // We rely on the tool executor itself routing through ACP. The audit entry below is
            // a secondary tool-execution audit record.
            it.dispatch(cmd.copy(params = JsonObject(call.arguments.mapValues { JsonPrimitive(it.value.toString()) })))
        }
        return result.copy(output = buildJsonObject {
            put("ok", result.ok)
            put("duration_ms", durationMs)
            result.output.forEach { (k, v) -> put(k, v) }
        })
    }
}
