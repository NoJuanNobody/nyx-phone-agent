package com.nyx.agent.skill

/**
 * Dispatches tool calls to the appropriate [Skill] via the [SkillRegistry].
 */
class SkillRouter(private val registry: SkillRegistry) {
    suspend fun dispatch(toolName: String, args: Map<String, Any>): SkillResult {
        val skill = registry.find(toolName) ?: return SkillResult.SkillNotFound(toolName)
        return skill.execute(args)
    }
}
