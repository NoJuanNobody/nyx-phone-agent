package com.nyx.agent.skill.impl

import com.nyx.agent.skill.SkillResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SmsSkillTest {

    private lateinit var bridge: SmsBridge
    private lateinit var skill: SmsSkill

    @Before
    fun setUp() {
        bridge = mockk()
        skill = SmsSkill(bridge)
    }

    @Test
    fun `read returns threads with contact names`() = runTest {
        val threads = listOf(
            SmsThread(
                contactName = "Alice",
                phoneNumber = "+15551234567",
                messages = listOf(
                    SmsMessage(body = "Hello!", isIncoming = true, timestampMs = 1_000L),
                    SmsMessage(body = "Hi there!", isIncoming = false, timestampMs = 2_000L),
                )
            ),
            SmsThread(
                contactName = null,
                phoneNumber = "+15559876543",
                messages = listOf(
                    SmsMessage(body = "Unknown sender", isIncoming = true, timestampMs = 3_000L),
                )
            ),
        )
        every { bridge.readThreads(any()) } returns threads

        val result = skill.execute(mapOf("action" to "read")) as SkillResult.Success

        assertEquals(2, result.output["thread_count"])
        @Suppress("UNCHECKED_CAST")
        val returnedThreads = result.output["threads"] as List<Map<String, Any?>>
        assertEquals("Alice", returnedThreads[0]["contact"])
        assertEquals(2, returnedThreads[0]["message_count"])
        assertEquals("Hi there!", returnedThreads[0]["last_message"])
        // Thread with no contact name falls back to phone number
        assertEquals("+15559876543", returnedThreads[1]["contact"])
    }

    @Test
    fun `read with limit = 5 passes limit to bridge`() = runTest {
        every { bridge.readThreads(5) } returns emptyList()

        skill.execute(mapOf("action" to "read", "limit" to 5))

        verify(exactly = 1) { bridge.readThreads(5) }
    }

    @Test
    fun `send returns Success with sent_to`() = runTest {
        every { bridge.sendSms("+15551234567", "Hey!") } returns Unit

        val result = skill.execute(mapOf(
            "action" to "send",
            "to" to "+15551234567",
            "body" to "Hey!",
        )) as SkillResult.Success

        assertEquals("+15551234567", result.output["sent_to"])
        assertEquals(4, result.output["body_length"])
    }

    @Test
    fun `send with missing to returns Failure`() = runTest {
        val result = skill.execute(mapOf(
            "action" to "send",
            "body" to "Hello",
        )) as SkillResult.Failure

        assertTrue(result.error.contains("'to'"))
    }

    @Test
    fun `send with missing body returns Failure`() = runTest {
        val result = skill.execute(mapOf(
            "action" to "send",
            "to" to "+15551234567",
        )) as SkillResult.Failure

        assertTrue(result.error.contains("'body'"))
    }

    @Test
    fun `send when bridge throws returns Failure`() = runTest {
        val exception = IllegalStateException("No SIM card present")
        every { bridge.sendSms(any(), any()) } throws exception

        val result = skill.execute(mapOf(
            "action" to "send",
            "to" to "+15551234567",
            "body" to "Test",
        )) as SkillResult.Failure

        assertTrue(result.error.contains("Failed to send SMS"))
        assertEquals(exception, result.cause)
    }

    @Test
    fun `unknown action returns Failure`() = runTest {
        val result = skill.execute(mapOf("action" to "delete")) as SkillResult.Failure

        assertTrue(result.error.contains("Unknown action"))
        assertTrue(result.error.contains("delete"))
    }

    @Test
    fun `skill metadata is correct`() {
        assertEquals("sms", skill.name)
        assertTrue(skill.requiresConfirmation)
        assertTrue(skill.requiredPermissions.contains("android.permission.READ_SMS"))
        assertTrue(skill.requiredPermissions.contains("android.permission.SEND_SMS"))
        assertTrue(skill.requiredPermissions.contains("android.permission.READ_CONTACTS"))
    }
}
