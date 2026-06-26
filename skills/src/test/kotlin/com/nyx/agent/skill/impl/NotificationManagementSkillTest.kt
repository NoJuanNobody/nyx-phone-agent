package com.nyx.agent.skill.impl

import com.nyx.agent.skill.SkillResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NotificationManagementSkillTest {

    private lateinit var bridge: NotificationBridge
    private lateinit var skill: NotificationManagementSkill

    private val sampleNotifications = listOf(
        NotificationInfo(
            id = 1,
            packageName = "com.example.app",
            title = "Test Title",
            text = "Secret body text that must not be logged",
            actions = listOf("Reply", "Mark as read"),
        ),
        NotificationInfo(
            id = 2,
            packageName = "com.another.app",
            title = "Another Title",
            text = "Another secret body",
            actions = emptyList(),
        ),
    )

    @BeforeEach
    fun setUp() {
        bridge = mockk()
        skill = NotificationManagementSkill(bridge)
    }

    @Test
    fun `list returns notification count and fields (excludes text body)`() = runBlocking {
        every { bridge.listNotifications() } returns sampleNotifications

        val result = skill.execute(mapOf("action" to "list"))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(2, output["count"])

        @Suppress("UNCHECKED_CAST")
        val notifications = output["notifications"] as List<Map<String, Any>>
        assertEquals(2, notifications.size)

        val first = notifications[0]
        assertEquals(1, first["id"])
        assertEquals("com.example.app", first["app"])
        assertEquals("Test Title", first["title"])
        assertEquals(listOf("Reply", "Mark as read"), first["actions"])
        // text body must NOT be present
        assertTrue(!first.containsKey("text"), "notification map must not expose text body")
    }

    @Test
    fun `dismiss with valid id returns Success`() = runBlocking {
        every { bridge.dismiss(1) } returns true

        val result = skill.execute(mapOf("action" to "dismiss", "notification_id" to 1))

        assertTrue(result is SkillResult.Success)
        assertEquals(1, (result as SkillResult.Success).output["dismissed_id"])
        verify(exactly = 1) { bridge.dismiss(1) }
    }

    @Test
    fun `dismiss with invalid id returns Failure`() = runBlocking {
        every { bridge.dismiss(99) } returns false

        val result = skill.execute(mapOf("action" to "dismiss", "notification_id" to 99))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("99"))
    }

    @Test
    fun `trigger_action with valid id and label returns Success`() = runBlocking {
        every { bridge.triggerAction(1, "Reply") } returns true

        val result = skill.execute(
            mapOf("action" to "trigger_action", "notification_id" to 1, "action_label" to "Reply")
        )

        assertTrue(result is SkillResult.Success)
        assertEquals("Reply", (result as SkillResult.Success).output["triggered"])
    }

    @Test
    fun `trigger_action with unknown label returns Failure`() = runBlocking {
        every { bridge.triggerAction(1, "Delete") } returns false

        val result = skill.execute(
            mapOf("action" to "trigger_action", "notification_id" to 1, "action_label" to "Delete")
        )

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("Delete"))
    }

    @Test
    fun `missing action arg returns Failure`() = runBlocking {
        val result = skill.execute(emptyMap())

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("Unknown action"))
    }

    @Test
    fun `missing notification_id for dismiss returns Failure`() = runBlocking {
        val result = skill.execute(mapOf("action" to "dismiss"))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("notification_id"))
    }

    @Test
    fun `missing notification_id for trigger_action returns Failure`() = runBlocking {
        val result = skill.execute(mapOf("action" to "trigger_action", "action_label" to "Reply"))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("notification_id"))
    }
}
