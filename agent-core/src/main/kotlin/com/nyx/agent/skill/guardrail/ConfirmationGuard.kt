package com.nyx.agent.skill.guardrail

import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

/**
 * Confirmation broker for skills with [Skill.requiresConfirmation] = true.
 *
 * In production, confirmation is obtained via a UI overlay or notification action.
 * Tests inject a [ConfirmationProvider] that resolves synchronously.
 */
interface ConfirmationProvider {
    suspend fun requestConfirmation(skillName: String, description: String): Boolean
}

/**
 * Wraps skill execution with a confirmation gate when [Skill.requiresConfirmation] is true.
 * Returns [SkillResult.ConfirmationDenied] if the user declines.
 */
class ConfirmationGuard(private val provider: ConfirmationProvider) {
    suspend fun gate(skill: Skill): SkillResult? {
        if (!skill.requiresConfirmation) return null
        val confirmed = provider.requestConfirmation(skill.name, skill.description)
        return if (confirmed) null else SkillResult.ConfirmationDenied(skill.name)
    }
}
