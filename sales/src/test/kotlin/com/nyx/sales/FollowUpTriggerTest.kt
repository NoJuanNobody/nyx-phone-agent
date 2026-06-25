package com.nyx.sales

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FollowUpTriggerTest {

    private val webhookUrl = "https://hooks.zapier.com/test/abc123"
    private val webhookClient = mockk<WebhookClient>()
    private val trigger = FollowUpTrigger(webhookUrl, webhookClient)

    private val deal = CRMDeal(id = "d-1", contactId = "c-1", title = "Acme deal", stage = "prospect")
    private val lead = LeadData(
        name = "Jane Doe",
        email = "jane@acme.com",
        company = "Acme Inc",
        budget = "$50k",
        timeline = "Q1 2025",
        painPoints = listOf("slow onboarding"),
        nextSteps = listOf("send proposal", "schedule demo"),
    )

    @Test
    fun `qualified lead fires webhook and returns true`() = runTest {
        val qualifiedScore = BantScore(budget = true, authority = true, need = true, timeline = true)
        coEvery { webhookClient.post(webhookUrl, any()) } returns true

        val result = trigger.trigger(deal, lead, qualifiedScore)

        assertTrue(result)
        coVerify(exactly = 1) { webhookClient.post(webhookUrl, any()) }
    }

    @Test
    fun `unqualified lead skips webhook and returns false`() = runTest {
        val unqualifiedScore = BantScore(budget = false, authority = true, need = false, timeline = false)
        // score.total = 25, isQualified = false

        val result = trigger.trigger(deal, lead, unqualifiedScore)

        assertFalse(result)
        coVerify(exactly = 0) { webhookClient.post(any(), any()) }
    }

    @Test
    fun `payload fields are populated correctly`() = runTest {
        val qualifiedScore = BantScore(budget = true, authority = true, need = true, timeline = true)
        val payloadSlot = slot<FollowUpPayload>()
        coEvery { webhookClient.post(webhookUrl, capture(payloadSlot)) } returns true

        trigger.trigger(deal, lead, qualifiedScore)

        val payload = payloadSlot.captured
        assertEquals(deal.id, payload.dealId)
        assertEquals(lead.name, payload.leadName)
        assertEquals(lead.email, payload.leadEmail)
        assertEquals(100, payload.bantScore)
        assertEquals(lead.nextSteps, payload.nextSteps)
        assertEquals("post_call_followup", payload.webhookTag)
    }

    @Test
    fun `webhook returning false propagates correctly`() = runTest {
        val qualifiedScore = BantScore(budget = true, authority = true, need = true, timeline = false)
        // score.total = 75, isQualified = true
        coEvery { webhookClient.post(webhookUrl, any()) } returns false

        val result = trigger.trigger(deal, lead, qualifiedScore)

        assertFalse(result)
    }
}
