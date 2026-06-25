package com.nyx.agent.skill.impl

import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

/**
 * Reference implementation showing how a destructive skill declares its permission and confirmation requirements.
 * Real destructive skills (SendSMSSkill, SystemControlsSkill) must follow this pattern.
 */
class ExampleDestructiveSkill : Skill {
    override val name = "example_destructive"
    override val description = "Demonstrates permission + confirmation guardrail pattern"
    override val requiredPermissions = listOf("android.permission.SEND_SMS")
    override val requiresConfirmation = true
    override suspend fun execute(args: Map<String, Any>): SkillResult =
        SkillResult.Success(mapOf("result" to "executed"))
}
