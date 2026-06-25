package com.nyx.agent.orchestration

/**
 * Converts a structured plan representation (JSON-like map from LLM output) into an [OrchestrationPlan].
 *
 * Expected LLM output format (as a list of maps):
 * [
 *   {"step": 1, "skill": "launch_app", "args": {"app_name": "Gmail"}, "description": "Open Gmail", "destructive": false},
 *   {"step": 2, "skill": "ui_interact", "args": {"action": "tap", "x": 240, "y": 880}, "description": "Tap compose", "destructive": false},
 * ]
 */
object PlanParser {
    /**
     * Parse a list of step maps (as produced by the LLM) into an [OrchestrationPlan].
     * Throws [IllegalArgumentException] if required fields are missing.
     */
    fun parse(goal: String, rawSteps: List<Map<String, Any>>): OrchestrationPlan {
        val steps = rawSteps.mapIndexed { idx, raw ->
            PlanStep(
                stepNumber = (raw["step"] as? Number)?.toInt() ?: (idx + 1),
                skillName = raw["skill"] as? String
                    ?: throw IllegalArgumentException("Step $idx missing 'skill'"),
                args = @Suppress("UNCHECKED_CAST") (raw["args"] as? Map<String, Any>) ?: emptyMap(),
                description = raw["description"] as? String ?: "",
                isDestructive = raw["destructive"] as? Boolean ?: false,
            )
        }
        return OrchestrationPlan(goal, steps)
    }
}
