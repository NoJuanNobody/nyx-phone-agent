package com.nyx.agent.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Risk tier assigned to an action by [RiskClassifier].
 *
 * - [LOW]: read-only / non-destructive (e.g. read SMS, query battery).
 *   Auto-allowed unless an explicit deny rule exists.
 * - [MEDIUM]: side effects but reversible (e.g. toggle Wi-Fi, dismiss
 *   notification). Requires [PolicyDecision.REQUIRE_CONFIRM].
 * - [HIGH]: destructive / irreversible / sensitive (e.g. send SMS, answer
 *   call, system setting change). Requires explicit user consent every time.
 */
@Serializable
enum class RiskTier { LOW, MEDIUM, HIGH }

/**
 * Decision returned by the Policy Engine for an action request.
 *
 * @property ALLOW Execute immediately.
 * @property DENY Never execute. Not overrideable by the LLM or any MCP tool;
 *                only a user changing the policy rule can re-permit it.
 * @property REQUIRE_CONFIRM Present a confirmation dialog to the user;
 *                action is not executed until the user responds.
 */
@Serializable
enum class PolicyDecision { ALLOW, DENY, REQUIRE_CONFIRM }

/**
 * Response shape returned by [PolicyEngine.evaluate].
 */
@Serializable
data class PolicyEvaluation(
    val decision: PolicyDecision,
    val riskTier: RiskTier,
    val matchedRuleId: String? = null,
    val reason: String
) {
    companion object {
        fun allow(risk: RiskTier, reason: String = "default allow") =
            PolicyEvaluation(PolicyDecision.ALLOW, risk, reason = reason)
        fun deny(risk: RiskTier, ruleId: String?, reason: String) =
            PolicyEvaluation(PolicyDecision.DENY, risk, ruleId, reason)
        fun confirm(risk: RiskTier, ruleId: String?, reason: String) =
            PolicyEvaluation(PolicyDecision.REQUIRE_CONFIRM, risk, ruleId, reason)
    }
}
