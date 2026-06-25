package com.nyx.agent.skill

/**
 * Contract for all agent skills.
 *
 * Implementations are registered with `SkillRegistry` and invoked by the agent
 * runtime when a matched intent is resolved.
 */
interface Skill {
    /** Unique snake_case identifier used to route invocations. */
    val name: String

    /** Human-readable description surfaced in help text and UI. */
    val description: String

    /**
     * Android permissions required before [execute] may be called.
     * The runtime checks these against granted permissions and returns
     * [SkillResult.PermissionDenied] if any are missing.
     */
    val requiredPermissions: List<String> get() = emptyList()

    /**
     * When `true` the runtime will prompt the user for confirmation before
     * calling [execute], returning [SkillResult.ConfirmationDenied] if refused.
     */
    val requiresConfirmation: Boolean get() = false

    /**
     * Execute the skill with the provided [args] map.
     *
     * @param args Key/value pairs specific to this skill (see each implementation's KDoc).
     * @return A [SkillResult] describing the outcome.
     */
    suspend fun execute(args: Map<String, Any>): SkillResult
}

/** Discriminated union representing every possible outcome of a skill execution. */
sealed class SkillResult {
    /** The skill completed successfully. [output] carries result data. */
    data class Success(val output: Map<String, Any> = emptyMap()) : SkillResult()

    /** The skill failed for a domain-level reason (e.g. app not found). */
    data class Failure(val error: String, val cause: Throwable? = null) : SkillResult()

    /** No registered skill matches the requested name. */
    data class SkillNotFound(val skillName: String) : SkillResult()

    /** The user declined the confirmation prompt. */
    data class ConfirmationDenied(val skillName: String) : SkillResult()

    /** One or more Android permissions required by the skill have not been granted. */
    data class PermissionDenied(
        val skillName: String,
        val missingPermissions: List<String>,
    ) : SkillResult()
}
