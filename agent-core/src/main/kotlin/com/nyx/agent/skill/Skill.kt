package com.nyx.agent.skill

/**
 * Base contract for all Nyx agent skills.
 * Implementations register with [SkillRegistry] and are dispatched by [SkillRouter].
 */
interface Skill {
    val name: String
    val description: String
    /** Android permissions this skill requires (e.g. "android.permission.SEND_SMS"). */
    val requiredPermissions: List<String> get() = emptyList()
    /** If true, SkillRouter must obtain explicit user confirmation before executing. */
    val requiresConfirmation: Boolean get() = false
    suspend fun execute(args: Map<String, Any>): SkillResult
}

sealed class SkillResult {
    data class Success(val output: Map<String, Any> = emptyMap()) : SkillResult()
    data class Failure(val error: String, val cause: Throwable? = null) : SkillResult()
    data class SkillNotFound(val skillName: String) : SkillResult()
    /** Returned when user declines the confirmation prompt. */
    data class ConfirmationDenied(val skillName: String) : SkillResult()
    /** Returned when a required Android permission is not granted. */
    data class PermissionDenied(val skillName: String, val missingPermissions: List<String>) : SkillResult()
}
