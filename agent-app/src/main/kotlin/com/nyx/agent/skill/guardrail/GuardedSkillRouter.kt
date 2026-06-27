package com.nyx.agent.skill.guardrail

import android.content.Context
import com.nyx.agent.skill.SkillRegistry
import com.nyx.agent.skill.SkillResult
import com.nyx.agent.skill.SkillRouter

/**
 * Drop-in replacement for [SkillRouter] that enforces permission and confirmation guards
 * before delegating to the underlying router.
 *
 * Enforcement order: PermissionGuard → ConfirmationGuard → SkillRouter.dispatch
 */
class GuardedSkillRouter(
    private val registry: SkillRegistry,
    private val context: Context,
    private val confirmationGuard: ConfirmationGuard,
) {
    private val router = SkillRouter(registry)

    suspend fun dispatch(toolName: String, args: Map<String, Any>): SkillResult {
        val skill = registry.find(toolName)
            ?: return SkillResult.SkillNotFound(toolName)

        // 1. Permission check
        PermissionGuard.check(context, skill)?.let { return it }

        // 2. Confirmation gate
        confirmationGuard.gate(skill)?.let { return it }

        // 3. Execute
        return router.dispatch(toolName, args)
    }
}
