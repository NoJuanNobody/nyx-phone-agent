package com.nyx.receptionist

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CRMContextInjectorTest {

    private val provider = mockk<CRMProvider>()
    private val injector = CRMContextInjector(provider)

    @Test
    fun `known caller returns formatted block with name, company, and summary`() = runTest {
        coEvery { provider.fetchCallerRecord("+15551234567") } returns CallerRecord(
            name = "Jane Doe",
            company = "Acme Corp",
            lastInteractionSummary = "Discussed Q3 renewal",
        )

        val block = injector.buildContextBlock("+15551234567")

        assertTrue(block.contains("CALLER CONTEXT:"))
        assertTrue(block.contains("Name: Jane Doe"))
        assertTrue(block.contains("Company: Acme Corp"))
        assertTrue(block.contains("Last interaction: Discussed Q3 renewal"))
    }

    @Test
    fun `unknown caller returns empty string`() = runTest {
        coEvery { provider.fetchCallerRecord("+10000000000") } returns null

        val block = injector.buildContextBlock("+10000000000")

        assertEquals("", block)
    }

    @Test
    fun `partial record with name only skips missing fields`() = runTest {
        coEvery { provider.fetchCallerRecord("+15559876543") } returns CallerRecord(
            name = "John Smith",
            company = null,
            lastInteractionSummary = null,
        )

        val block = injector.buildContextBlock("+15559876543")

        assertTrue(block.contains("Name: John Smith"))
        assertTrue(!block.contains("Company:"))
        assertTrue(!block.contains("Last interaction:"))
    }

    @Test
    fun `tags are included when present`() = runTest {
        coEvery { provider.fetchCallerRecord("+15550001111") } returns CallerRecord(
            name = "Alice",
            company = "StartupCo",
            lastInteractionSummary = null,
            tags = listOf("VIP", "enterprise"),
        )

        val block = injector.buildContextBlock("+15550001111")

        assertTrue(block.contains("Tags: VIP, enterprise"))
    }
}
