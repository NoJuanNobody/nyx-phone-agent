package com.nyx.agent.intent

import com.nyx.agent.mcp.McpRegistry
import com.nyx.agent.mcp.ToolCall
import com.nyx.agent.mcp.ToolDescriptor
import com.nyx.agent.mcp.ToolExecutor
import com.nyx.agent.mcp.ToolParam
import com.nyx.agent.mcp.ToolRisk
import com.nyx.agent.mcp.ToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Intent Bridge")
class IntentBridgeTest {

    private fun registry(): McpRegistry {
        val r = McpRegistry()
        r.register(
            ToolDescriptor("telephony.answer", "Answer call", listOf(ToolParam("call_id", "string", "call id")), ToolRisk.HIGH, "telephony"),
            ToolExecutor { ToolResult(true, buildJsonObject { put("ok", true) }) }
        )
        return r
    }

    @Test
    fun `parser extracts tool call from mixed text`() {
        val raw = """Sure! {"name":"telephony.answer","arguments":{"call_id":"c1"}}"""
        val tc = LlmOutputParser.extractFirstToolCall(raw)
        assertNotNull(tc)
        assertEquals("telephony.answer", tc!!.name)
        assertEquals("c1", tc.arguments["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parser handles multiple tool calls`() {
        val raw = """{"name":"telephony.answer","arguments":{"call_id":"c1"}} and {"name":"telephony.answer","arguments":{"call_id":"c2"}}"""
        assertEquals(2, LlmOutputParser.extractToolCalls(raw).size)
    }

    @Test
    fun `parser returns null on no json`() {
        assertNull(LlmOutputParser.extractFirstToolCall("just text, no calls"))
    }

    @Test
    fun `parser tolerates partial trailing json`() {
        // balanced object extracted, trailing partial ignored
        val raw = """{"name":"telephony.answer","arguments":{"call_id":"c1"}} {"name":"x"""
        assertEquals(1, LlmOutputParser.extractToolCalls(raw).size)
    }

    @Test
    fun `entity resolver resolves single contact`() {
        val r = EntityResolver(InMemoryContactsProvider(listOf(Contact("Sarah Connor", "555-1111"))))
        val res = r.resolveContact("Sarah")
        assertTrue(res is ContactResolution.Resolved)
        assertEquals("555-1111", (res as ContactResolution.Resolved).number)
    }

    @Test
    fun `entity resolver flags ambiguity`() {
        val r = EntityResolver(InMemoryContactsProvider(listOf(Contact("John Smith", "1"), Contact("John Doe", "2"))))
        val res = r.resolveContact("John")
        assertTrue(res is ContactResolution.Ambiguous)
        assertEquals(2, (res as ContactResolution.Ambiguous).matches.size)
        val q = AmbiguityResolver.disambiguationQuestion(res)
        assertTrue(q.contains("John Smith") && q.contains("John Doe"))
    }

    @Test
    fun `entity resolver not found`() {
        val r = EntityResolver(InMemoryContactsProvider())
        assertTrue(r.resolveContact("Ghost") is ContactResolution.NotFound)
    }

    @Test
    fun `resolve time in minutes`() {
        val fixed = 1_000_000L
        val r = EntityResolver(clock = { fixed })
        assertEquals(fixed + 5 * 60_000L, r.resolveTime("in 5 minutes"))
    }

    @Test
    fun `resolve time tomorrow 3pm`() {
        val fixed = 1_000_000L
        val r = EntityResolver(clock = { fixed })
        val ts = r.resolveTime("tomorrow 15:00")
        assertNotNull(ts)
        assertEquals(fixed + 86_400_000L + 15 * 3_600_000L, ts)
    }

    @Test
    fun `intent bridge processes valid call`() {
        val bridge = IntentBridge(registry())
        val out = bridge.process("""{"name":"telephony.answer","arguments":{"call_id":"c1"}}""")
        assertTrue(out is IntentBridge.Outcome.Ready)
        assertEquals("c1", (out as IntentBridge.Outcome.Ready).call.arguments["call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `intent bridge reports error on no call`() {
        val bridge = IntentBridge(registry())
        assertTrue(bridge.process("hello") is IntentBridge.Outcome.Error)
    }

    @Test
    fun `retry policy exhausts and escalates`() {
        val policy = IntentRetryPolicy(maxRetries = 2)
        val attempts = mutableListOf<Int>()
        val res = policy.runWithRetry({ attempt, _ -> attempts.add(attempt) }) { Result.failure<Int>(RuntimeException("bad")) }
        assertTrue(res.isFailure)
        assertEquals(listOf(1, 2), attempts)
        assertTrue(res.exceptionOrNull()!!.message!!.contains("escalating to human"))
    }

    @Test
    fun `retry policy succeeds on second attempt`() {
        val policy = IntentRetryPolicy(maxRetries = 3)
        var tries = 0
        val res = policy.runWithRetry({ _, _ -> }) {
            tries++; if (tries < 2) Result.failure(RuntimeException("x")) else Result.success("ok")
        }
        assertEquals("ok", res.getOrNull())
    }
}
