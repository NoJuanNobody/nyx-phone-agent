package com.nyx.agent.skill.impl

import com.nyx.agent.skill.SkillResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UiInteractionSkillTest {

    private lateinit var bridge: UiInteractionBridge
    private lateinit var auditLog: MutableList<GestureAuditEntry>
    private lateinit var skill: UiInteractionSkill

    @BeforeEach
    fun setUp() {
        bridge = mockk()
        auditLog = mutableListOf()
        skill = UiInteractionSkill(bridge, auditLog)
    }

    @Test
    fun `tap dispatches and returns Success`() = runTest {
        every { bridge.tap(100f, 200f) } returns true

        val result = skill.execute(mapOf("action" to "tap", "x" to 100, "y" to 200))

        assertTrue(result is SkillResult.Success)
        val success = result as SkillResult.Success
        assertEquals("tap", success.output["action"])
        assertEquals(100f, success.output["x"])
        assertEquals(200f, success.output["y"])
        verify(exactly = 1) { bridge.tap(100f, 200f) }
    }

    @Test
    fun `tap failure returns Failure`() = runTest {
        every { bridge.tap(any(), any()) } returns false

        val result = skill.execute(mapOf("action" to "tap", "x" to 50, "y" to 75))

        assertTrue(result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertTrue(failure.error.contains("tap"))
    }

    @Test
    fun `scroll requires end_x and end_y`() = runTest {
        // Missing both end_x and end_y
        val result = skill.execute(mapOf("action" to "scroll", "x" to 100, "y" to 200))
        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("end_x"))
    }

    @Test
    fun `scroll requires end_y when end_x is present`() = runTest {
        val result = skill.execute(mapOf("action" to "scroll", "x" to 100, "y" to 200, "end_x" to 100))
        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("end_y"))
    }

    @Test
    fun `scroll dispatches with correct params`() = runTest {
        every { bridge.scroll(100f, 500f, 100f, 100f, 300L) } returns true

        val result = skill.execute(
            mapOf(
                "action" to "scroll",
                "x" to 100, "y" to 500,
                "end_x" to 100, "end_y" to 100,
                "duration_ms" to 300,
            )
        )

        assertTrue(result is SkillResult.Success)
        verify(exactly = 1) { bridge.scroll(100f, 500f, 100f, 100f, 300L) }
    }

    @Test
    fun `swipe dispatches with correct params`() = runTest {
        every { bridge.swipe(0f, 400f, 300f, 400f, 200L) } returns true

        val result = skill.execute(
            mapOf(
                "action" to "swipe",
                "x" to 0, "y" to 400,
                "end_x" to 300, "end_y" to 400,
            )
        )

        assertTrue(result is SkillResult.Success)
        verify(exactly = 1) { bridge.swipe(0f, 400f, 300f, 400f, 200L) }
    }

    @Test
    fun `every dispatch appends to auditLog`() = runTest {
        every { bridge.tap(any(), any()) } returns true
        every { bridge.scroll(any(), any(), any(), any(), any()) } returns true

        skill.execute(mapOf("action" to "tap", "x" to 10, "y" to 20))
        skill.execute(
            mapOf("action" to "scroll", "x" to 0, "y" to 500, "end_x" to 0, "end_y" to 100)
        )

        assertEquals(2, auditLog.size)
        assertEquals("tap", auditLog[0].action)
        assertEquals("scroll", auditLog[1].action)
    }

    @Test
    fun `missing x coordinate returns Failure`() = runTest {
        val result = skill.execute(mapOf("action" to "tap", "y" to 200))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("x"))
    }

    @Test
    fun `missing y coordinate returns Failure`() = runTest {
        val result = skill.execute(mapOf("action" to "tap", "x" to 100))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("y"))
    }

    @Test
    fun `missing action returns Failure`() = runTest {
        val result = skill.execute(mapOf("x" to 100, "y" to 200))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("action"))
    }

    @Test
    fun `unknown action returns Failure`() = runTest {
        val result = skill.execute(mapOf("action" to "pinch", "x" to 100, "y" to 200))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("pinch"))
    }

    @Test
    fun `element_context included in audit entry`() = runTest {
        every { bridge.tap(any(), any()) } returns true

        skill.execute(
            mapOf(
                "action" to "tap",
                "x" to 240, "y" to 880,
                "element_context" to "button:Submit at (240,880)",
            )
        )

        assertEquals(1, auditLog.size)
        assertEquals("button:Submit at (240,880)", auditLog[0].elementContext)
    }

    @Test
    fun `audit entry contains success flag on successful dispatch`() = runTest {
        every { bridge.tap(any(), any()) } returns true

        skill.execute(mapOf("action" to "tap", "x" to 100, "y" to 200))

        assertTrue(auditLog[0].success)
    }

    @Test
    fun `audit entry contains success=false on failed dispatch`() = runTest {
        every { bridge.tap(any(), any()) } returns false

        skill.execute(mapOf("action" to "tap", "x" to 100, "y" to 200))

        assertFalse(auditLog[0].success)
    }

    @Test
    fun `null element_context stored in audit entry when not provided`() = runTest {
        every { bridge.tap(any(), any()) } returns true

        skill.execute(mapOf("action" to "tap", "x" to 100, "y" to 200))

        assertNull(auditLog[0].elementContext)
    }

    @Test
    fun `skill name and description are set correctly`() {
        assertEquals("ui_interact", skill.name)
        assertNotNull(skill.description)
        assertTrue(skill.description.isNotBlank())
    }

    @Test
    fun `duration_ms defaults to 200 when not specified`() = runTest {
        every { bridge.swipe(any(), any(), any(), any(), 200L) } returns true

        val result = skill.execute(
            mapOf("action" to "swipe", "x" to 0, "y" to 0, "end_x" to 100, "end_y" to 100)
        )

        assertTrue(result is SkillResult.Success)
        verify { bridge.swipe(any(), any(), any(), any(), 200L) }
    }
}
