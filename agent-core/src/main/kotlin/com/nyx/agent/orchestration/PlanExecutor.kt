package com.nyx.agent.orchestration

import com.nyx.agent.skill.SkillRegistry
import com.nyx.agent.skill.SkillResult

/**
 * Executes an [OrchestrationPlan] step-by-step.
 *
 * Behaviour:
 * - Passes prior step outputs as resolved args (replaces "{step_N.key}" placeholders).
 * - On failure, retries once before recording the failure and aborting.
 * - Destructive steps require [onDestructiveConfirmation] to return true.
 * - Records every step in [ExecutionTrace].
 */
class PlanExecutor(
    private val registry: SkillRegistry,
    private val onDestructiveConfirmation: suspend (PlanStep) -> Boolean = { false },
) {
    /**
     * Execute [plan] and return the completed [ExecutionTrace].
     * Stops at the first unrecoverable failure (after one retry).
     */
    suspend fun execute(plan: OrchestrationPlan): ExecutionTrace {
        val trace = ExecutionTrace()
        val stepOutputs = mutableMapOf<Int, Map<String, Any>>()  // stepNumber -> output

        for (step in plan.steps) {
            // Resolve arg placeholders
            val resolvedArgs = resolveArgs(step.args, stepOutputs)

            // Gate destructive steps
            if (step.isDestructive && !onDestructiveConfirmation(step)) {
                trace.record(
                    StepTrace(
                        stepNumber = step.stepNumber,
                        skillName = step.skillName,
                        description = step.description,
                        startedAtMs = System.currentTimeMillis(),
                        completedAtMs = System.currentTimeMillis(),
                        result = SkillResult.ConfirmationDenied(step.skillName),
                    )
                )
                break
            }

            // Execute with one retry
            val startedAt = System.currentTimeMillis()
            var result = dispatchStep(step, resolvedArgs)
            val retried: Boolean
            if (result is SkillResult.Failure) {
                result = dispatchStep(step, resolvedArgs)
                retried = true
            } else {
                retried = false
            }

            val completedAt = System.currentTimeMillis()
            trace.record(
                StepTrace(
                    stepNumber = step.stepNumber,
                    skillName = step.skillName,
                    description = step.description,
                    startedAtMs = startedAt,
                    completedAtMs = completedAt,
                    result = result,
                    retried = retried,
                )
            )

            if (result is SkillResult.Success) {
                stepOutputs[step.stepNumber] = result.output
            } else {
                break  // abort on unrecoverable failure
            }
        }
        return trace
    }

    private suspend fun dispatchStep(step: PlanStep, args: Map<String, Any>): SkillResult {
        val skill = registry.find(step.skillName) ?: return SkillResult.SkillNotFound(step.skillName)
        return try {
            skill.execute(args)
        } catch (e: Exception) {
            SkillResult.Failure(e.message ?: "unknown", e)
        }
    }

    private fun resolveArgs(args: Map<String, Any>, outputs: Map<Int, Map<String, Any>>): Map<String, Any> {
        return args.mapValues { (_, v) ->
            if (v is String && v.startsWith("{step_")) {
                // pattern: {step_N.key}
                val match = Regex("\\{step_(\\d+)\\.(.+)\\}").matchEntire(v)
                if (match != null) {
                    val n = match.groupValues[1].toInt()
                    val key = match.groupValues[2]
                    outputs[n]?.get(key) ?: v
                } else v
            } else v
        }
    }
}
