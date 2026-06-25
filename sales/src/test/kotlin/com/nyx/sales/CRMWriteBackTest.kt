package com.nyx.sales

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CRMWriteBackTest {

    private val client = mockk<CRMClient>()
    private val writeBack = CRMWriteBack(client)

    private val lead = LeadData(
        name = "Jane Doe",
        email = "jane@acme.com",
        company = "Acme Inc",
        budget = "$50k",
        timeline = "Q1 2025",
        painPoints = listOf("manual processes", "slow onboarding"),
        nextSteps = listOf("send proposal"),
    )
    private val contact = CRMContact(id = "c-1", email = lead.email, name = lead.name)
    private val deal = CRMDeal(id = "d-1", contactId = "c-1", title = "Acme deal", stage = "prospect")
    private val score = BantScore(budget = true, authority = true, need = true, timeline = true)

    @Test
    fun `writeLead calls upsertContact, createDeal, addNote in order`() = runTest {
        coEvery { client.upsertContact(lead) } returns contact
        coEvery { client.createDeal(contact, lead, score) } returns deal
        coEvery { client.addNote(deal.id, any()) } just Runs

        val result = writeBack.writeLead(lead, "some transcript", score)

        assertEquals(deal, result)
        coVerifyOrder {
            client.upsertContact(lead)
            client.createDeal(contact, lead, score)
            client.addNote(deal.id, any())
        }
    }

    @Test
    fun `note contains BANT score and pain points`() = runTest {
        val noteSlot = slot<String>()
        coEvery { client.upsertContact(lead) } returns contact
        coEvery { client.createDeal(contact, lead, score) } returns deal
        coEvery { client.addNote(deal.id, capture(noteSlot)) } just Runs

        writeBack.writeLead(lead, "transcript text", score)

        val note = noteSlot.captured
        assertTrue(note.contains("BANT score: 100/100"), "note should contain BANT score")
        assertTrue(note.contains("qualified=true"), "note should contain qualification status")
        assertTrue(note.contains("manual processes"), "note should contain pain points")
        assertTrue(note.contains("send proposal"), "note should contain next steps")
    }

    @Test
    fun `note truncates transcript longer than 2000 chars`() = runTest {
        val longTranscript = "x".repeat(5000)
        val noteSlot = slot<String>()
        coEvery { client.upsertContact(lead) } returns contact
        coEvery { client.createDeal(contact, lead, score) } returns deal
        coEvery { client.addNote(deal.id, capture(noteSlot)) } just Runs

        writeBack.writeLead(lead, longTranscript, score)

        val note = noteSlot.captured
        // The transcript portion in the note should not exceed 2000 chars
        val transcriptPortion = note.substringAfter("---\n")
        assertTrue(transcriptPortion.length <= 2000, "transcript should be truncated to 2000 chars")
    }

    @Test
    fun `note does not include pain points section when list is empty`() = runTest {
        val leadNoPain = lead.copy(painPoints = emptyList(), nextSteps = emptyList())
        val scoreNoPain = BantScore(budget = true, authority = true, need = false, timeline = true)
        val noteSlot = slot<String>()
        coEvery { client.upsertContact(leadNoPain) } returns contact
        coEvery { client.createDeal(contact, leadNoPain, scoreNoPain) } returns deal
        coEvery { client.addNote(deal.id, capture(noteSlot)) } just Runs

        writeBack.writeLead(leadNoPain, "short transcript", scoreNoPain)

        assertFalse(noteSlot.captured.contains("Pain points:"))
        assertFalse(noteSlot.captured.contains("Next steps:"))
    }
}
