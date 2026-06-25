package com.nyx.agent.acp.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import com.nyx.agent.acp.auth.HmacAuthenticator

@DisplayName("ACP Message Builder")
class AcpMessageBuilderTest {

    private val auth = HmacAuthenticator.fromSecret("test-secret")
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `build request message with signature`() {
        val msg = AcpMessageBuilder(auth)
            .request("client-1", buildJsonObject { put("action", "ping") })
            .build()
        assertEquals(MessageType.REQUEST, msg.messageType)
        assertEquals("client-1", msg.clientId)
        assertTrue(msg.signature.isNotEmpty())
        assertTrue(auth.verify(msg))
    }

    @Test
    fun `build heartbeat message`() {
        val msg = AcpMessageBuilder(auth)
            .heartbeat("client-1")
            .build()
        assertEquals(MessageType.HEARTBEAT, msg.messageType)
        assertTrue(auth.verify(msg))
    }

    @Test
    fun `build event message`() {
        val msg = AcpMessageBuilder(auth)
            .event("server", JsonPrimitive("event-data"))
            .build()
        assertEquals(MessageType.EVENT, msg.messageType)
        assertTrue(auth.verify(msg))
    }

    @Test
    fun `buildJson returns valid JSON string`() {
        val raw = AcpMessageBuilder(auth)
            .request("c1", buildJsonObject { put("x", 1) })
            .buildJson(json)
        val parsed = json.parseToJsonElement(raw)
        assertTrue(parsed.toString().contains("\"requestId\""))
        assertTrue(parsed.toString().contains("\"signature\""))
    }

    @Test
    fun `custom version can be set`() {
        val msg = AcpMessageBuilder(auth)
            .version("2.0.0")
            .request("c1", JsonPrimitive("data"))
            .build()
        assertEquals("2.0.0", msg.version)
    }

    @Test
    fun `protocol version parse and compatibility`() {
        val v1 = ProtocolVersion.parse("1.2.3")
        assertEquals(1, v1.major)
        assertEquals(2, v1.minor)
        assertEquals(3, v1.patch)

        val v1b = ProtocolVersion.parse("1.5.0")
        assertTrue(v1.isCompatibleWith(v1b))

        val v2 = ProtocolVersion.parse("2.0.0")
        assertTrue(!v1.isCompatibleWith(v2))
    }
}
