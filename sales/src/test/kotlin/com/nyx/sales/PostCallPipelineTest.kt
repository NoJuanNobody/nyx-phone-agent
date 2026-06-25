package com.nyx.sales

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PostCallPipelineTest {

    private val mockParser = mockk<TranscriptParser>()
    private val mockCRMClient = mockk<CRMClient>()
    private val mockWebhookClient = mockk<WebhookClient>()

    private val extractor = LeadExtractor(mockParser)
    private val scorer = QualificationScorer()
    private val crmWriteBack = CRMWriteBack(mockCRMClient)
    private val followUpTrigger = FollowUpTrigger("https://hooks.zapier.com/test", mockWebhookClient)

    private val pipeline = PostCallPipeline(extractor, scorer, crmWriteBack, followUpTrigger)

    private val transcript = "Hi, I'm Jane from Acme. We need help with onboarding, budget is 50k, timeline Q1."

    private val extractedLead = LeadData(
        name = "Jane Doe",
        email = "jane@acme.com",
        company = "Acme Inc",
        budget = "$50k",
        timeline = "Q1 2025",
        painPoints = listOf("slow onboarding"),
        nextSteps = listOf("send proposal"),
    )
    private val contact = CRMContact(id = "c-42", email = "jane@acme.com", name = "Jane Doe")
    private val deal = CRMDeal(id = "d-99", contactId = "c-42", title = "Acme deal", stage = "new")

    @Test
    fun `full pipeline runs all stages and returns complete result`() = runTest {
        coEvery { mockParser.extractLead(transcript) } returns extractedLead
        coEvery { mockCRMClient.upsertContact(extractedLead) } returns contact
        coEvery { mockCRMClient.createDeal(contact, extractedLead, any()) } returns deal
        coEvery { mockCRMClient.addNote(deal.id, any()) } just Runs
        coEvery { mockWebhookClient.post(any(), any()) } returns true

        val result = pipeline.run(transcript)

        assertEquals(extractedLead, result.lead)
        assertEquals(deal, result.deal)
        assertTrue(result.score.isQualified)
        assertTrue(result.followUpFired)
    }

    @Test
    fun `pipeline sets followUpFired false for unqualified lead`() = runTest {
        val unqualifiedLead = LeadData(
            name = null,
            email = null,
            company = null,
            budget = null,
            timeline = null,
            painPoints = emptyList(),
        )
        coEvery { mockParser.extractLead(transcript) } returns unqualifiedLead
        coEvery { mockCRMClient.upsertContact(unqualifiedLead) } returns contact
        coEvery { mockCRMClient.createDeal(contact, unqualifiedLead, any()) } returns deal
        coEvery { mockCRMClient.addNote(deal.id, any()) } just Runs

        val result = pipeline.run(transcript)

        assertFalse(result.score.isQualified)
        assertFalse(result.followUpFired)
        coVerify(exactly = 0) { mockWebhookClient.post(any(), any()) }
    }

    @Test
    fun `pipeline result contains correct BANT score`() = runTest {
        coEvery { mockParser.extractLead(transcript) } returns extractedLead
        coEvery { mockCRMClient.upsertContact(extractedLead) } returns contact
        coEvery { mockCRMClient.createDeal(contact, extractedLead, any()) } returns deal
        coEvery { mockCRMClient.addNote(deal.id, any()) } just Runs
        coEvery { mockWebhookClient.post(any(), any()) } returns true

        val result = pipeline.run(transcript)

        assertEquals(100, result.score.total)
        assertTrue(result.score.budget)
        assertTrue(result.score.authority)
        assertTrue(result.score.need)
        assertTrue(result.score.timeline)
    }

    @Test
    fun `pipeline stages execute in correct order`() = runTest {
        coEvery { mockParser.extractLead(transcript) } returns extractedLead
        coEvery { mockCRMClient.upsertContact(extractedLead) } returns contact
        coEvery { mockCRMClient.createDeal(contact, extractedLead, any()) } returns deal
        coEvery { mockCRMClient.addNote(deal.id, any()) } just Runs
        coEvery { mockWebhookClient.post(any(), any()) } returns true

        pipeline.run(transcript)

        coVerifyOrder {
            mockParser.extractLead(transcript)
            mockCRMClient.upsertContact(extractedLead)
            mockCRMClient.createDeal(contact, extractedLead, any())
            mockCRMClient.addNote(deal.id, any())
            mockWebhookClient.post(any(), any())
        }
    }
}
