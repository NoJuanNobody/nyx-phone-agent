package com.nyx.agent.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A policy rule: trigger (action type + optional context predicate) maps to
 * a response decision.
 *
 * Rules are evaluated in priority order (highest first). The first matching
 * rule wins. If no rule matches, the [RiskClassifier] tier determines the
 * default decision (LOW → ALLOW, MEDIUM → REQUIRE_CONFIRM, HIGH → REQUIRE_CONFIRM).
 *
 * @property id Stable rule identifier.
 * @property actionType Action type this rule applies to (e.g. "sms.send").
 * @property decision Decision when this rule matches.
 * @property priority Higher priority rules are evaluated first.
 * @property contextPredicate Optional predicate over the action context
 *          (e.g. "only during business hours"). Null matches all contexts.
 * @property description Human-readable description.
 */
@Serializable
data class PolicyRule(
    @SerialName("id") val id: String,
    @SerialName("action_type") val actionType: String,
    @SerialName("decision") val decision: PolicyDecision,
    @SerialName("priority") val priority: Int = 0,
    @SerialName("description") val description: String = "",
    @SerialName("context") val contextPredicateKind: String? = null
) {
    /**
     * Whether this rule matches the given action context.
     * @param context arbitrary key/value context (e.g. recipient, time).
     */
    fun matches(actionType: String, context: Map<String, String>): Boolean {
        if (this.actionType != actionType && this.actionType != "*") return false
        return contextPredicateKind?.let { evaluateContext(it, context) } ?: true
    }

    private fun evaluateContext(kind: String, context: Map<String, String>): Boolean = when (kind) {
        "business_hours" -> {
            val hour = context["hour"]?.toIntOrNull() ?: return false
            hour in 9..17
        }
        "whitelist" -> context["whitelisted"] == "true"
        else -> true
    }
}
