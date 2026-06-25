package com.nyx.agent.skill

interface Skill {
    val name: String
    val description: String
    val requiredPermissions: List<String> get() = emptyList()
    val requiresConfirmation: Boolean get() = false
    suspend fun execute(args: Map<String, Any>): SkillResult
}
sealed class SkillResult {
    data class Success(val output: Map<String, Any> = emptyMap()) : SkillResult()
    data class Failure(val error: String, val cause: Throwable? = null) : SkillResult()
    data class SkillNotFound(val skillName: String) : SkillResult()
    data class ConfirmationDenied(val skillName: String) : SkillResult()
    data class PermissionDenied(val skillName: String, val missingPermissions: List<String>) : SkillResult()
}
