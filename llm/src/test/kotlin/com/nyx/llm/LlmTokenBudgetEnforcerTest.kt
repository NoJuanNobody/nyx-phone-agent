package com.nyx.llm

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LlmTokenBudgetEnforcerTest {

    private lateinit var mockEngine: LlmInferenceEngine
    private lateinit var enforcer: LlmTokenBudgetEnforcer

    @Before
    fun setUp() {
        mockEngine = mockk(relaxed = true)
        enforcer = LlmTokenBudgetEnforcer(engine = mockEngine, maxOutputTokens = 128)
    }

    @Test
    fun `generate caps maxTokens to maxOutputTokens when caller requests more`() = runTest {
        coEvery { mockEngine.generate(any(), any()) } returns "response"
        enforcer.generate("prompt", maxTokens = 512)
        coVerify { mockEngine.generate("prompt", 128) }
    }

    @Test
    fun `generate uses caller maxTokens when below cap`() = runTest {
        coEvery { mockEngine.generate(any(), any()) } returns "response"
        enforcer.generate("prompt", maxTokens = 64)
        coVerify { mockEngine.generate("prompt", 64) }
    }

    @Test
    fun `generate returns engine result`() = runTest {
        coEvery { mockEngine.generate(any(), any()) } returns "expected output"
        val result = enforcer.generate("prompt", maxTokens = 512)
        assertEquals("expected output", result)
    }

    @Test
    fun `stream caps maxTokens to maxOutputTokens`() {
        every { mockEngine.stream(any(), any()) } returns flowOf("token")
        enforcer.stream("prompt", maxTokens = 1024)
        verify { mockEngine.stream("prompt", 128) }
    }

    @Test
    fun `stream uses caller maxTokens when below cap`() {
        every { mockEngine.stream(any(), any()) } returns flowOf("token")
        enforcer.stream("prompt", maxTokens = 50)
        verify { mockEngine.stream("prompt", 50) }
    }

    @Test
    fun `close is forwarded to wrapped engine`() {
        enforcer.close()
        verify { mockEngine.close() }
    }
}
