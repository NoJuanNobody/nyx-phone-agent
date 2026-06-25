package com.nyx.agent.orchestration

import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillRegistry
import com.nyx.agent.skill.SkillResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlanExecutorTest {

    private fun makeStep(
        number: Int,
        skillName: String,
        args: Map<String, Any> = emptyMap(),
        description: String = "Step $number",
        isDestructive: Boolean = false,
    ) = PlanStep(number, skillName, args, description, isDestructive)

    private fun makePlan(goal: String, vararg steps: PlanStep) =
        OrchestrationPlan(goal, steps.toList())

    private fun registryWith(vararg skills: Skill): SkillRegistry {
        val registry = SkillRegistry()
        skills.forEach { registry.register(it) }
        return registry
    }

    private fun stubSkill(skillName: String, result: SkillResult): Skill = object : Skill {
        override val name = skillName
        override val description = "stub"
        override suspend fun execute(args: Map<String, Any>) = result
    }

    private fun callCountingSkill(skillName: String, results: List<SkillResult>): Pair<Skill, () -> Int> {
        var count = 0
        val skill = object : Skill {
            override val name = skillName
            override val description = "counting stub"
            override suspend fun execute(args: Map<String, Any>): SkillResult {
                return results[count++]
            }
        }
        return skill to { count }
    }

    @Test
    fun `sequential execution passes outputs to next step`() = runTest {
        val step1Skill = object : Skill {
            override val name = "step1"
            override val description = "step1"
            override suspend fun execute(args: Map<String, Any>) =
                SkillResult.Success(mapOf("url" to "https://example.com"))
        }
        val capturedArgs = mutableMapOf<String, Any>()
        val step2Skill = object : Skill {
            override val name = "step2"
            override val description = "step2"
            override suspend fun execute(args: Map<String, Any>): SkillResult {
                capturedArgs.putAll(args)
                return SkillResult.Success()
            }
        }

        val registry = registryWith(step1Skill, step2Skill)
        val executor = PlanExecutor(registry)
        val plan = makePlan(
            "two steps",
            makeStep(1, "step1"),
            makeStep(2, "step2", args = mapOf("target" to "{step_1.url}")),
        )

        val trace = executor.execute(plan)

        assertEquals(2, trace.all().size)
        assertEquals("https://example.com", capturedArgs["target"])
        assertTrue(trace.failed().isEmpty())
    }

    @Test
    fun `retries once on failure then succeeds`() = runTest {
        val (skill, getCount) = callCountingSkill(
            "flaky",
            listOf(SkillResult.Failure("transient"), SkillResult.Success(mapOf("done" to true))),
        )

        val registry = registryWith(skill)
        val executor = PlanExecutor(registry)
        val plan = makePlan("retry goal", makeStep(1, "flaky"))

        val trace = executor.execute(plan)

        assertEquals(1, trace.all().size)
        val entry = trace.all()[0]
        assertIs<SkillResult.Success>(entry.result)
        assertTrue(entry.retried)
        assertEquals(2, getCount())
    }

    @Test
    fun `aborts after retry fails`() = runTest {
        val (skill, getCount) = callCountingSkill(
            "broken",
            listOf(SkillResult.Failure("first"), SkillResult.Failure("second")),
        )
        val step2Executed = mutableListOf<Boolean>()
        val step2Skill = object : Skill {
            override val name = "step2"
            override val description = "step2"
            override suspend fun execute(args: Map<String, Any>): SkillResult {
                step2Executed.add(true)
                return SkillResult.Success()
            }
        }

        val registry = registryWith(skill, step2Skill)
        val executor = PlanExecutor(registry)
        val plan = makePlan("fail goal", makeStep(1, "broken"), makeStep(2, "step2"))

        val trace = executor.execute(plan)

        assertEquals(1, trace.all().size)
        assertIs<SkillResult.Failure>(trace.all()[0].result)
        assertTrue(trace.all()[0].retried)
        assertEquals(2, getCount())
        assertTrue(step2Executed.isEmpty(), "step2 should not have been executed")
    }

    @Test
    fun `destructive step is blocked when confirmation returns false`() = runTest {
        val destructiveSkill = stubSkill("delete", SkillResult.Success())
        val registry = registryWith(destructiveSkill)
        val executor = PlanExecutor(registry, onDestructiveConfirmation = { false })
        val plan = makePlan("delete goal", makeStep(1, "delete", isDestructive = true))

        val trace = executor.execute(plan)

        assertEquals(1, trace.all().size)
        assertIs<SkillResult.ConfirmationDenied>(trace.all()[0].result)
    }

    @Test
    fun `destructive step proceeds when confirmation returns true`() = runTest {
        val destructiveSkill = stubSkill("delete", SkillResult.Success(mapOf("deleted" to true)))
        val registry = registryWith(destructiveSkill)
        val executor = PlanExecutor(registry, onDestructiveConfirmation = { true })
        val plan = makePlan("delete goal", makeStep(1, "delete", isDestructive = true))

        val trace = executor.execute(plan)

        assertEquals(1, trace.all().size)
        assertIs<SkillResult.Success>(trace.all()[0].result)
    }

    @Test
    fun `trace records all steps with timestamps`() = runTest {
        val skill1 = stubSkill("s1", SkillResult.Success())
        val skill2 = stubSkill("s2", SkillResult.Success())
        val registry = registryWith(skill1, skill2)
        val executor = PlanExecutor(registry)
        val plan = makePlan("goal", makeStep(1, "s1"), makeStep(2, "s2"))

        val trace = executor.execute(plan)

        assertEquals(2, trace.all().size)
        trace.all().forEach { entry ->
            assertTrue(entry.startedAtMs > 0)
            assertTrue(entry.completedAtMs >= entry.startedAtMs)
        }
    }

    @Test
    fun `unknown skill results in SkillNotFound and aborts`() = runTest {
        val registry = SkillRegistry()
        val executor = PlanExecutor(registry)
        val plan = makePlan("goal", makeStep(1, "nonexistent"))

        val trace = executor.execute(plan)

        assertEquals(1, trace.all().size)
        assertIs<SkillResult.SkillNotFound>(trace.all()[0].result)
    }

    @Test
    fun `non-destructive steps do not invoke confirmation`() = runTest {
        var confirmationCalled = false
        val skill = stubSkill("safe", SkillResult.Success())
        val registry = registryWith(skill)
        val executor = PlanExecutor(registry, onDestructiveConfirmation = {
            confirmationCalled = true
            true
        })
        val plan = makePlan("safe goal", makeStep(1, "safe", isDestructive = false))

        executor.execute(plan)

        assertFalse(confirmationCalled)
    }
}
