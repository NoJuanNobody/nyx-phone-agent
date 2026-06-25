package com.nyx.agent.orchestration

import com.nyx.agent.skill.SkillResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionTraceTest {

    private fun makeTrace(
        stepNumber: Int,
        skillName: String = "skill",
        result: SkillResult = SkillResult.Success(),
        retried: Boolean = false,
    ) = StepTrace(
        stepNumber = stepNumber,
        skillName = skillName,
        description = "Step $stepNumber",
        startedAtMs = 1000L,
        completedAtMs = 2000L,
        result = result,
        retried = retried,
    )

    @Test
    fun `all returns all recorded traces`() {
        val trace = ExecutionTrace()
        trace.record(makeTrace(1))
        trace.record(makeTrace(2))

        assertEquals(2, trace.all().size)
        assertEquals(1, trace.all()[0].stepNumber)
        assertEquals(2, trace.all()[1].stepNumber)
    }

    @Test
    fun `failed returns only failure traces`() {
        val trace = ExecutionTrace()
        trace.record(makeTrace(1, result = SkillResult.Success()))
        trace.record(makeTrace(2, result = SkillResult.Failure("oops")))
        trace.record(makeTrace(3, result = SkillResult.Success()))
        trace.record(makeTrace(4, result = SkillResult.Failure("again")))

        val failed = trace.failed()
        assertEquals(2, failed.size)
        assertEquals(2, failed[0].stepNumber)
        assertEquals(4, failed[1].stepNumber)
    }

    @Test
    fun `all returns empty list when no traces recorded`() {
        val trace = ExecutionTrace()
        assertTrue(trace.all().isEmpty())
    }

    @Test
    fun `failed returns empty list when all steps succeed`() {
        val trace = ExecutionTrace()
        trace.record(makeTrace(1, result = SkillResult.Success()))
        trace.record(makeTrace(2, result = SkillResult.Success()))

        assertTrue(trace.failed().isEmpty())
    }

    @Test
    fun `all returns snapshot and is not affected by later records`() {
        val trace = ExecutionTrace()
        trace.record(makeTrace(1))
        val snapshot = trace.all()
        trace.record(makeTrace(2))

        assertEquals(1, snapshot.size)
        assertEquals(2, trace.all().size)
    }
}
