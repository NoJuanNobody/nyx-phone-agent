package com.nyx.agent.orchestration

/**
 * Immutable record of one step's execution outcome.
 */
data class StepTrace(
    val stepNumber: Int,
    val skillName: String,
    val description: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val result: com.nyx.agent.skill.SkillResult,
    val retried: Boolean = false,
)

/**
 * Accumulates [StepTrace] entries for a full orchestration run.
 */
class ExecutionTrace {
    private val entries = mutableListOf<StepTrace>()
    fun record(trace: StepTrace) { entries.add(trace) }
    fun all(): List<StepTrace> = entries.toList()
    fun failed(): List<StepTrace> = entries.filter { it.result is com.nyx.agent.skill.SkillResult.Failure }
}
