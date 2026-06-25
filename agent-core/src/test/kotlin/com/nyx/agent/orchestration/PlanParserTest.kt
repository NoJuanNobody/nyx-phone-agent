package com.nyx.agent.orchestration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanParserTest {

    @Test
    fun `parse valid steps produces correct OrchestrationPlan`() {
        val rawSteps = listOf(
            mapOf("step" to 1, "skill" to "launch_app", "args" to mapOf("app_name" to "Gmail"), "description" to "Open Gmail", "destructive" to false),
            mapOf("step" to 2, "skill" to "ui_interact", "args" to mapOf("action" to "tap", "x" to 240, "y" to 880), "description" to "Tap compose", "destructive" to false),
        )

        val plan = PlanParser.parse("Send an email", rawSteps)

        assertEquals("Send an email", plan.goal)
        assertEquals(2, plan.steps.size)

        val step1 = plan.steps[0]
        assertEquals(1, step1.stepNumber)
        assertEquals("launch_app", step1.skillName)
        assertEquals(mapOf("app_name" to "Gmail"), step1.args)
        assertEquals("Open Gmail", step1.description)
        assertFalse(step1.isDestructive)

        val step2 = plan.steps[1]
        assertEquals(2, step2.stepNumber)
        assertEquals("ui_interact", step2.skillName)
    }

    @Test
    fun `parse throws on missing skill field`() {
        val rawSteps = listOf(
            mapOf("step" to 1, "description" to "Missing skill", "args" to emptyMap<String, Any>()),
        )

        assertThrows<IllegalArgumentException> {
            PlanParser.parse("goal", rawSteps)
        }
    }

    @Test
    fun `parse handles empty steps list`() {
        val plan = PlanParser.parse("do nothing", emptyList())

        assertEquals("do nothing", plan.goal)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun `parse uses index plus one when step number is absent`() {
        val rawSteps = listOf(
            mapOf("skill" to "skill_a", "args" to emptyMap<String, Any>(), "description" to "first"),
            mapOf("skill" to "skill_b", "args" to emptyMap<String, Any>(), "description" to "second"),
        )

        val plan = PlanParser.parse("goal", rawSteps)

        assertEquals(1, plan.steps[0].stepNumber)
        assertEquals(2, plan.steps[1].stepNumber)
    }

    @Test
    fun `parse marks destructive steps correctly`() {
        val rawSteps = listOf(
            mapOf("step" to 1, "skill" to "delete_file", "args" to emptyMap<String, Any>(), "description" to "Delete file", "destructive" to true),
        )

        val plan = PlanParser.parse("delete", rawSteps)

        assertTrue(plan.steps[0].isDestructive)
    }

    @Test
    fun `parse defaults destructive to false when absent`() {
        val rawSteps = listOf(
            mapOf("step" to 1, "skill" to "safe_skill", "args" to emptyMap<String, Any>(), "description" to "Safe"),
        )

        val plan = PlanParser.parse("safe goal", rawSteps)

        assertFalse(plan.steps[0].isDestructive)
    }

    @Test
    fun `parse defaults args to empty map when absent`() {
        val rawSteps = listOf(
            mapOf("step" to 1, "skill" to "no_args_skill", "description" to "No args"),
        )

        val plan = PlanParser.parse("goal", rawSteps)

        assertEquals(emptyMap<String, Any>(), plan.steps[0].args)
    }
}
