package com.nyx.sales

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QualificationScorerTest {

    private val scorer = QualificationScorer()

    private fun fullLead() = LeadData(
        name = "Jane Doe",
        email = "jane@acme.com",
        company = "Acme Inc",
        budget = "$50k",
        timeline = "Q1 2025",
        painPoints = listOf("manual processes", "slow onboarding"),
        nextSteps = listOf("send proposal", "schedule demo"),
    )

    @Test
    fun `all four BANT signals present scores 100`() {
        val score = scorer.score(fullLead())
        assertEquals(100, score.total)
        assertTrue(score.budget)
        assertTrue(score.authority)
        assertTrue(score.need)
        assertTrue(score.timeline)
        assertTrue(score.isQualified)
    }

    @Test
    fun `no BANT signals scores 0`() {
        val lead = LeadData(
            name = null,
            email = null,
            company = null,
            budget = null,
            timeline = null,
            painPoints = emptyList(),
            nextSteps = emptyList(),
        )
        val score = scorer.score(lead)
        assertEquals(0, score.total)
        assertFalse(score.isQualified)
    }

    @Test
    fun `two signals present scores 50 and is qualified`() {
        val lead = LeadData(
            name = "John Smith",
            email = null,
            company = null,
            budget = "$10k",
            timeline = null,
            painPoints = emptyList(),
        )
        val score = scorer.score(lead)
        assertEquals(50, score.total)
        assertTrue(score.isQualified)
    }

    @Test
    fun `one signal present scores 25 and is not qualified`() {
        val lead = LeadData(
            name = "Alice",
            email = null,
            company = null,
            budget = null,
            timeline = null,
            painPoints = emptyList(),
        )
        val score = scorer.score(lead)
        assertEquals(25, score.total)
        assertFalse(score.isQualified)
    }

    @Test
    fun `blank budget string is treated as missing`() {
        val lead = fullLead().copy(budget = "   ")
        val score = scorer.score(lead)
        assertFalse(score.budget)
        assertEquals(75, score.total)
    }

    @Test
    fun `empty pain points list means need is false`() {
        val lead = fullLead().copy(painPoints = emptyList())
        val score = scorer.score(lead)
        assertFalse(score.need)
        assertEquals(75, score.total)
    }
}
