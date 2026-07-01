package com.nyx.agent.skill.guardrail

import android.content.Context
import android.content.pm.PackageManager
import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillRegistry
import com.nyx.agent.skill.SkillResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GuardedSkillRouterTest {

    private lateinit var registry: SkillRegistry
    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        registry = SkillRegistry()
        context = mockk()
    }

    private fun makeRouter(confirmed: Boolean = true): GuardedSkillRouter {
        val provider = object : ConfirmationProvider {
            override suspend fun requestConfirmation(skillName: String, description: String) = confirmed
        }
        return GuardedSkillRouter(registry, context, ConfirmationGuard(provider))
    }

    // Helper: stub ContextCompat.checkSelfPermission via the Context mock.
    // ContextCompat delegates to context.checkPermission under the hood on API 23+.
    private fun grantPermission(permission: String) {
        every {
            context.checkPermission(permission, any(), any())
        } returns PackageManager.PERMISSION_GRANTED
    }

    private fun denyPermission(permission: String) {
        every {
            context.checkPermission(permission, any(), any())
        } returns PackageManager.PERMISSION_DENIED
    }

    // --- Test skills ---

    private val noGuardsSkill = object : Skill {
        override val name = "no_guards"
        override val description = "A simple skill with no permissions or confirmation"
        override suspend fun execute(args: Map<String, Any>) =
            SkillResult.Success(mapOf("ok" to true))
    }

    private val permissionedSkill = object : Skill {
        override val name = "permissioned"
        override val description = "Requires SMS permission"
        override val requiredPermissions = listOf("android.permission.SEND_SMS")
        override suspend fun execute(args: Map<String, Any>) =
            SkillResult.Success(mapOf("ok" to true))
    }

    private val confirmationSkill = object : Skill {
        override val name = "needs_confirm"
        override val description = "Requires user confirmation"
        override val requiresConfirmation = true
        override suspend fun execute(args: Map<String, Any>) =
            SkillResult.Success(mapOf("ok" to true))
    }

    private val bothGuardsSkill = object : Skill {
        override val name = "both_guards"
        override val description = "Requires permission AND confirmation"
        override val requiredPermissions = listOf("android.permission.SEND_SMS")
        override val requiresConfirmation = true
        override suspend fun execute(args: Map<String, Any>) =
            SkillResult.Success(mapOf("ok" to true))
    }

    @Test
    fun `skill with no permissions and no confirmation dispatches normally`() = runBlocking {
        registry.register(noGuardsSkill)
        val router = makeRouter()

        val result = router.dispatch("no_guards", emptyMap())

        assertTrue(result is SkillResult.Success)
    }

    @Test
    fun `skill with missing permission returns PermissionDenied`() = runBlocking {
        registry.register(permissionedSkill)
        denyPermission("android.permission.SEND_SMS")
        val router = makeRouter()

        val result = router.dispatch("permissioned", emptyMap())

        assertTrue(result is SkillResult.PermissionDenied)
        val denied = result as SkillResult.PermissionDenied
        assertEquals("permissioned", denied.skillName)
        assertTrue(denied.missingPermissions.contains("android.permission.SEND_SMS"))
    }

    @Test
    fun `skill with confirmation required and user confirms dispatches normally`() = runBlocking {
        registry.register(confirmationSkill)
        val router = makeRouter(confirmed = true)

        val result = router.dispatch("needs_confirm", emptyMap())

        assertTrue(result is SkillResult.Success)
    }

    @Test
    fun `skill with confirmation required and user denies returns ConfirmationDenied`() = runBlocking {
        registry.register(confirmationSkill)
        val router = makeRouter(confirmed = false)

        val result = router.dispatch("needs_confirm", emptyMap())

        assertTrue(result is SkillResult.ConfirmationDenied)
        val denied = result as SkillResult.ConfirmationDenied
        assertEquals("needs_confirm", denied.skillName)
    }

    @Test
    fun `permission check happens before confirmation prompt`() = runBlocking {
        registry.register(bothGuardsSkill)
        denyPermission("android.permission.SEND_SMS")
        // Even though confirmed = true, permission should fail first
        val router = makeRouter(confirmed = true)

        val result = router.dispatch("both_guards", emptyMap())

        // Must be PermissionDenied, NOT ConfirmationDenied
        assertTrue(result is SkillResult.PermissionDenied, "Expected PermissionDenied but got $result")
        val denied = result as SkillResult.PermissionDenied
        assertEquals("both_guards", denied.skillName)
    }
}
