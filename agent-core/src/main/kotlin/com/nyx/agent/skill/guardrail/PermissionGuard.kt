package com.nyx.agent.skill.guardrail

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

/**
 * Checks that all [Skill.requiredPermissions] are granted before dispatch.
 * Returns [SkillResult.PermissionDenied] listing missing permissions if any are absent.
 */
object PermissionGuard {
    fun check(context: Context, skill: Skill): SkillResult.PermissionDenied? {
        val missing = skill.requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_DENIED
        }
        return if (missing.isEmpty()) null
        else SkillResult.PermissionDenied(skill.name, missing)
    }
}
