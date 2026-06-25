package com.nyx.agent.orchestration

/**
 * A parsed orchestration plan: an ordered sequence of [PlanStep]s derived from a natural-language goal.
 */
data class OrchestrationPlan(
    val goal: String,
    val steps: List<PlanStep>,
)

/**
 * A single step in an orchestration plan.
 * @param skillName the tool-call name to dispatch
 * @param args the arguments to pass; may reference prior step outputs as "{step_N.output_key}"
 * @param description human-readable description for tracing
 * @param isDestructive if true, requires safety guardrail confirmation before execution
 */
data class PlanStep(
    val stepNumber: Int,
    val skillName: String,
    val args: Map<String, Any>,
    val description: String,
    val isDestructive: Boolean = false,
)
