package com.nyx.agent.skill

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SkillRouterTest {

    private lateinit var registry: SkillRegistry
    private lateinit var router: SkillRouter

    @BeforeEach
    fun setUp() {
        registry = SkillRegistry()
        router = SkillRouter(registry)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun stubSkill(
        skillName: String,
        result: SkillResult = SkillResult.Success(mapOf("status" to "ok")),
    ): Skill = mockk<Skill> {
        coEvery { name } returns skillName
        coEvery { description } returns "stub skill: $skillName"
        coEvery { execute(any()) } returns result
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `dispatch to registered skill returns Success`() = runBlocking {
        val skill = stubSkill("launch_app", SkillResult.Success(mapOf("launched" to true)))
        registry.register(skill)

        val result = router.dispatch("launch_app", mapOf("package" to "com.example"))

        assertTrue(result is SkillResult.Success, "Expected Success but got $result")
        assertEquals(mapOf("launched" to true), (result as SkillResult.Success).output)
    }

    @Test
    fun `dispatch to unknown skill returns SkillNotFound`() = runBlocking {
        val result = router.dispatch("nonexistent_skill", emptyMap())

        assertTrue(result is SkillResult.SkillNotFound, "Expected SkillNotFound but got $result")
        assertEquals("nonexistent_skill", (result as SkillResult.SkillNotFound).skillName)
    }

    @Test
    fun `dispatch handles skill throwing exception returns Failure`() = runBlocking {
        val throwingSkill = mockk<Skill> {
            coEvery { name } returns "bad_skill"
            coEvery { description } returns "a skill that throws"
            coEvery { execute(any()) } throws RuntimeException("something went wrong")
        }
        registry.register(throwingSkill)

        val result = router.dispatch("bad_skill", emptyMap())

        assertTrue(result is SkillResult.Failure, "Expected Failure but got $result")
        val failure = result as SkillResult.Failure
        assertNotNull(failure.cause)
        assertTrue(failure.error.contains("bad_skill"))
        assertTrue(failure.cause is RuntimeException)
        assertEquals("something went wrong", failure.cause!!.message)
    }

    @Test
    fun `concurrent dispatches do not corrupt registry`() = runBlocking {
        // Register 10 skills, then dispatch all of them concurrently.
        val skillCount = 10
        repeat(skillCount) { i ->
            registry.register(stubSkill("skill_$i", SkillResult.Success(mapOf("index" to i))))
        }

        val results = (0 until skillCount).map { i ->
            async(Dispatchers.Default) {
                router.dispatch("skill_$i", emptyMap())
            }
        }.awaitAll()

        // Every result must be a Success — no SkillNotFound or Failure.
        results.forEachIndexed { i, result ->
            assertTrue(
                result is SkillResult.Success,
                "Expected Success for skill_$i but got $result",
            )
        }
        // All 10 skills are still registered after concurrent access.
        assertEquals(skillCount, registry.all().size)
    }

    @Test
    fun `unregistered skill after deregistration returns SkillNotFound`() = runBlocking {
        val skill = stubSkill("send_sms")
        registry.register(skill)
        registry.unregister("send_sms")

        val result = router.dispatch("send_sms", emptyMap())

        assertTrue(result is SkillResult.SkillNotFound)
        assertEquals("send_sms", (result as SkillResult.SkillNotFound).skillName)
    }

    @Test
    fun `dispatch passes args to skill execute`() = runBlocking {
        val capturedArgs = mutableMapOf<String, Any>()
        val skill = mockk<Skill> {
            coEvery { name } returns "echo_skill"
            coEvery { description } returns "echoes args"
            coEvery { execute(any()) } answers {
                capturedArgs.putAll(firstArg<Map<String, Any>>())
                SkillResult.Success(capturedArgs.toMap())
            }
        }
        registry.register(skill)

        val inputArgs = mapOf("message" to "hello", "recipient" to "+15550001234")
        router.dispatch("echo_skill", inputArgs)

        assertEquals(inputArgs, capturedArgs)
    }
}
