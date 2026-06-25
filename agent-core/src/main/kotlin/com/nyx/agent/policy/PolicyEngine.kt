package com.nyx.agent.policy

/**
 * The Policy Engine — Nyx's trust layer.
 *
 * Every action the agent wants to take must pass through [evaluate] before
 * execution. The engine loads active rules from [PolicyStore], finds the
 * first matching rule by priority, and returns its decision. With no
 * matching rule, the [RiskClassifier] tier decides the default:
 *   LOW            → ALLOW
 *   MEDIUM / HIGH  → REQUIRE_CONFIRM
 *
 * [evaluate] is the synchronous fast path (< 10ms). Confirmation dialogs and
 * audit signing happen out-of-band via [ConsentGate] and [AuditLog].
 *
 * DENY decisions are absolute: not overrideable by the LLM or any MCP tool.
 */
class PolicyEngine(
    private val store: PolicyStore,
    private val classifier: RiskClassifier = RiskClassifier
) {
    /**
     * Evaluate an action request.
     *
     * @param actionType The action type (e.g. "sms.send").
     * @param context Arbitrary context used by rule predicates.
     * @return The policy evaluation including decision, risk tier, and reason.
     */
    fun evaluate(actionType: String, context: Map<String, String> = emptyMap()): PolicyEvaluation {
        val tier = classifier.classify(actionType)
        val rules = store.load()
        val matched = rules.firstOrNull { it.matches(actionType, context) }
        return when {
            matched != null -> PolicyEvaluation(matched.decision, tier, matched.id, "rule ${matched.id}")
            tier == RiskTier.LOW -> PolicyEvaluation.allow(tier)
            else -> PolicyEvaluation.confirm(tier, null, "default confirm for $tier")
        }
    }
}
