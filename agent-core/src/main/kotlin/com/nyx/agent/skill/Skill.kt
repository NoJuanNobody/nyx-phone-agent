package com.nyx.agent.skill

/**
 * Base contract for all Nyx agent skills.
 *
 * Implementations register with [SkillRegistry] and are dispatched by [SkillRouter].
 * Each skill must be stateless or manage its own thread-safety.
 */
interface Skill {
    /** Unique tool-call name (e.g. "launch_app", "send_sms"). */
    val name: String

    /** Human-readable description surfaced in the tool manifest. */
    val description: String

    /**
     * Execute this skill asynchronously.
     *
     * Implementations must never throw — any error should be returned as [SkillResult.Failure].
     *
     * @param args Key-value arguments from the model tool-call.
     * @return The result of executing this skill.
     */
    suspend fun execute(args: Map<String, Any>): SkillResult
}

/** Result type returned by every skill execution. */
sealed class SkillResult {
    /** Skill executed successfully, optionally returning structured output. */
    data class Success(val output: Map<String, Any> = emptyMap()) : SkillResult()

    /** Skill encountered an error during execution. */
    data class Failure(val error: String, val cause: Throwable? = null) : SkillResult()

    /** No skill with the given name is registered in [SkillRegistry]. */
    data class SkillNotFound(val skillName: String) : SkillResult()
}
