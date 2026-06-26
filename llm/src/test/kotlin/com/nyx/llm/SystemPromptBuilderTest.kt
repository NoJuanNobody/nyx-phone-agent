package com.nyx.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SystemPromptBuilderTest {

    private lateinit var builder: SystemPromptBuilder

    @Before
    fun setUp() {
        builder = SystemPromptBuilder()
    }

    @Test
    fun `default build contains default persona`() {
        val result = builder.build()
        assertTrue(result.contains("You are Nyx, an on-device AI phone agent."))
    }

    @Test
    fun `default build has no timestamp section when blank`() {
        val result = builder.build()
        assertFalse(result.contains("Current time:"))
    }

    @Test
    fun `build includes timestamp when provided`() {
        val result = builder.build(timestamp = "2026-06-25T12:00:00Z")
        assertTrue(result.contains("Current time: 2026-06-25T12:00:00Z"))
    }

    @Test
    fun `build includes custom persona`() {
        builder.persona = "You are a helpful assistant."
        val result = builder.build()
        assertTrue(result.contains("You are a helpful assistant."))
    }

    @Test
    fun `build lists tools when toolCatalog is set`() {
        builder.toolCatalog = listOf("web_search", "calendar_read")
        val result = builder.build()
        assertTrue(result.contains("Available tools:"))
        assertTrue(result.contains("  - web_search"))
        assertTrue(result.contains("  - calendar_read"))
    }

    @Test
    fun `build omits tools section when toolCatalog is empty`() {
        val result = builder.build()
        assertFalse(result.contains("Available tools:"))
    }

    @Test
    fun `build lists policy constraints when set`() {
        builder.policyConstraints = listOf("No PII retention", "Max 30s response")
        val result = builder.build()
        assertTrue(result.contains("Active constraints:"))
        assertTrue(result.contains("  - No PII retention"))
        assertTrue(result.contains("  - Max 30s response"))
    }

    @Test
    fun `build omits constraints section when empty`() {
        val result = builder.build()
        assertFalse(result.contains("Active constraints:"))
    }

    @Test
    fun `build with all fields produces complete prompt`() {
        builder.persona = "You are Nyx."
        builder.toolCatalog = listOf("tool_a")
        builder.policyConstraints = listOf("constraint_1")
        val result = builder.build(timestamp = "T0")
        assertTrue(result.contains("SYSTEM: You are Nyx."))
        assertTrue(result.contains("Current time: T0"))
        assertTrue(result.contains("  - tool_a"))
        assertTrue(result.contains("  - constraint_1"))
    }
}
