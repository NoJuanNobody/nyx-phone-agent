package com.nyx.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContextManagerTest {

    private lateinit var contextManager: ContextManager

    @Before
    fun setUp() {
        contextManager = ContextManager(maxTokens = 100)
    }

    @Test
    fun `addTurn increases size`() {
        contextManager.addTurn("user", "Hello")
        assertEquals(1, contextManager.size())
        contextManager.addTurn("assistant", "Hi there!")
        assertEquals(2, contextManager.size())
    }

    @Test
    fun `buildPrompt formats turns correctly`() {
        contextManager.addTurn("user", "Hello")
        contextManager.addTurn("assistant", "Hi there!")
        val prompt = contextManager.buildPrompt()
        assertEquals("user: Hello\nassistant: Hi there!", prompt)
    }

    @Test
    fun `estimatedTokens returns rough char-divided-by-4 count`() {
        // "user: Hello" => role(4) + content(5) + 2 = 11 chars, 11/4 = 2
        contextManager.addTurn("user", "Hello")
        val tokens = contextManager.estimatedTokens()
        assertTrue("Expected positive token estimate", tokens > 0)
    }

    @Test
    fun `clear removes all turns`() {
        contextManager.addTurn("user", "Hello")
        contextManager.addTurn("assistant", "Hi")
        contextManager.clear()
        assertEquals(0, contextManager.size())
        assertEquals("", contextManager.buildPrompt())
    }

    @Test
    fun `evicts oldest non-system turns when over token limit`() {
        // Small token budget to trigger eviction
        val smallManager = ContextManager(maxTokens = 10)
        smallManager.addTurn("system", "System prompt")
        // Add turns that will exceed the token budget
        smallManager.addTurn("user", "This is a fairly long message that should push us over the token limit")
        smallManager.addTurn("assistant", "And this is an equally long response that should also consume tokens")
        // System prompt must be retained, old non-system turns should be evicted
        val prompt = smallManager.buildPrompt()
        assertTrue("System prompt must be retained", prompt.contains("system: System prompt"))
    }

    @Test
    fun `buildPrompt returns empty string when no turns`() {
        assertEquals("", contextManager.buildPrompt())
    }

    @Test
    fun `size returns zero on fresh instance`() {
        assertEquals(0, contextManager.size())
    }
}
