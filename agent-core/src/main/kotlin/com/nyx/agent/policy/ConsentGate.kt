package com.nyx.agent.policy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Consent gate — presents a user confirmation dialog for REQUIRE_CONFIRM
 * actions and awaits the user's response.
 *
 * In production this drives an Android UI dialog. For testing, a
 * [ConfirmationAdapter] is injected so the gate is fully controllable.
 *
 * DENY decisions from the Policy Engine are never overrideable by the LLM
 * or any MCP tool — only a user changing the policy rule can re-permit.
 */
fun interface ConfirmationAdapter {
    /**
     * Show a confirmation prompt and return the user's decision.
     * Implementations should return within 500ms of presentation per spec.
     */
    suspend fun prompt(actionType: String, message: String): Boolean
}

/**
 * Default consent gate backed by a [ConfirmationAdapter].
 */
class ConsentGate(private val adapter: ConfirmationAdapter) {

    /**
     * Request confirmation for an action. Returns true if the user approved.
     *
     * @param timeoutMs Prompt timeout (default 30s). A timeout counts as denial.
     */
    suspend fun confirm(actionType: String, message: String, timeoutMs: Long = 30_000L): Boolean =
        withTimeoutOrNull(timeoutMs) { adapter.prompt(actionType, message) } ?: false
}
