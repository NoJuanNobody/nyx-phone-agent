package com.nyx.agent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("MCP Tool Registry")
class McpRegistryTest {

    private fun pingTool(): ToolDescriptor = ToolDescriptor(
        name = "system.ping",
        description = "Ping the agent",
        params = listOf(ToolParam("msg", "string", "message to echo", required = true)),
        risk = ToolRisk.LOW,
        namespace = "system"
    )

    @Test
    fun `registry registers and lists tools`() {
        val r = McpRegistry()
        r.register(pingTool(), ToolExecutor { ToolResult(true, buildJsonObject { put("echo", "pong") }) })
        assertEquals(1, r.listTools().size)
        assertTrue(r.contains("system.ping"))
    }

    @Test
    fun `advertiser serializes catalog in mcp format`() {
        val json = McpCapabilityAdvertiser.serialize(listOf(pingTool()))
        assertTrue(json.contains("\"tools\""))
        assertTrue(json.contains("\"system.ping\""))
        assertTrue(json.contains("\"msg\""))
    }

    @Test
    fun `validator rejects missing required arg`() {
        val err = JsonSchemaValidator.validate(pingTool(), buildJsonObject {})
        assertNotNull(err)
        assertTrue(err!!.contains("Missing required argument 'msg'"))
    }

    @Test
    fun `validator rejects wrong type`() {
        val err = JsonSchemaValidator.validate(
            pingTool(),
            buildJsonObject { put("msg", 42) }  // number, not string
        )
        assertNotNull(err)
        assertTrue(err!!.contains("must be of type 'string'"))
    }

    @Test
    fun `validator accepts valid args`() {
        val err = JsonSchemaValidator.validate(pingTool(), buildJsonObject { put("msg", "hi") })
        assertNull(err)
    }

    @Test
    fun `sandbox enforces timeout`() = runBlocking {
        val sandbox = McpToolSandbox(timeoutMs = 100)
        val r = sandbox.run(ToolCall("slow", buildJsonObject {}), ToolExecutor {
            delay(5_000); ToolResult(true, buildJsonObject {})
        })
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("timed out"))
    }

    @Test
    fun `sandbox isolates exceptions`() = runBlocking {
        val sandbox = McpToolSandbox()
        val r = sandbox.run(ToolCall("boom", buildJsonObject {}), ToolExecutor { error("kaboom") })
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("kaboom"))
    }

    @Test
    fun `executor runs end to end`() = runBlocking {
        val registry = McpRegistry()
        registry.register(pingTool(), ToolExecutor { call ->
            val msg = call.arguments["msg"]!!.jsonPrimitive.content
            ToolResult(true, buildJsonObject { put("echo", msg) })
        })
        val store = com.nyx.agent.policy.InMemoryPolicyStore()
        val policy = com.nyx.agent.policy.PolicyEngine(store)
        val exec = McpExecutor(policy, registry, McpToolSandbox())
        val r = exec.execute(ToolCall("system.ping", buildJsonObject { put("msg", "hello") }))
        assertTrue(r.ok)
        assertEquals("hello", r.output["echo"]!!.jsonPrimitive.content)
    }

    @Test
    fun `executor refuses unknown tool`() = runBlocking {
        val exec = McpExecutor(
            com.nyx.agent.policy.PolicyEngine(com.nyx.agent.policy.InMemoryPolicyStore()),
            McpRegistry(), McpToolSandbox()
        )
        val r = exec.execute(ToolCall("nope", buildJsonObject {}))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("unknown tool"))
    }
}
