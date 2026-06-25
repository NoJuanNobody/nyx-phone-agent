package com.nyx.agent.acp.auth

import com.nyx.agent.acp.schema.ACPMessage
import com.nyx.agent.acp.schema.MessageType
import com.nyx.agent.acp.schema.ProtocolVersion
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HMAC Authenticator")
class HmacAuthenticatorTest {

    private val secret = "test-secret-key-for-hmac"
    private lateinit var authenticator: HmacAuthenticator
    private val auditEvents = mutableListOf<SecurityAuditEvent>()

    @BeforeEach
    fun setUp() {
        auditEvents.clear()
        authenticator = HmacAuthenticator(
            secret = secret.toByteArray(),
            securityAuditSink = { auditEvents.add(it) }
        )
    }

    private fun buildMessage(
        requestId: String = "req-1",
        clientId: String = "client-1",
        payload: kotlinx.serialization.json.JsonElement = buildJsonObject { put("action", "ping") }
    ): ACPMessage {
        return ACPMessage(
            version = ProtocolVersion.CURRENT.toString(),
            timestamp = System.currentTimeMillis(),
            requestId = requestId,
            clientId = clientId,
            messageType = MessageType.REQUEST,
            payload = payload,
            signature = ""
        )
    }

    @Test
    fun `sign and verify a valid message`() {
        val unsigned = buildMessage()
        val signed = authenticator.signMessage(unsigned)
        assertTrue(authenticator.verify(signed))
    }

    @Test
    fun `verify returns false for tampered payload`() {
        val unsigned = buildMessage()
        val signed = authenticator.signMessage(unsigned)
        val tampered = signed.copy(payload = JsonPrimitive("tampered"))
        assertFalse(authenticator.verify(tampered))
    }

    @Test
    fun `verify returns false for tampered timestamp`() {
        val unsigned = buildMessage()
        val signed = authenticator.signMessage(unsigned)
        val tampered = signed.copy(timestamp = signed.timestamp + 1)
        assertFalse(authenticator.verify(tampered))
    }

    @Test
    fun `verify returns false for tampered clientId`() {
        val unsigned = buildMessage()
        val signed = authenticator.signMessage(unsigned)
        val tampered = signed.copy(clientId = "attacker")
        assertFalse(authenticator.verify(tampered))
    }

    @Test
    fun `verify returns false for wrong secret`() {
        val unsigned = buildMessage()
        val signed = authenticator.signMessage(unsigned)
        val wrongAuthenticator = HmacAuthenticator("wrong-secret".toByteArray())
        assertFalse(wrongAuthenticator.verify(signed))
    }

    @Test
    fun `failed verification emits security audit event`() {
        val unsigned = buildMessage()
        val tampered = unsigned.copy(payload = JsonPrimitive("tampered"), signature = authenticator.sign(unsigned))
        val result = authenticator.verify(tampered)
        assertFalse(result)
        assertEquals(1, auditEvents.size)
        val event = auditEvents.first()
        assertEquals("req-1", event.requestId)
        assertEquals("client-1", event.clientId)
        assertTrue(event.reason.contains("signature mismatch"))
        assertNotNull(event.timestamp)
    }

    @Test
    fun `successful verification does not emit audit event`() {
        val unsigned = buildMessage()
        val signed = authenticator.signMessage(unsigned)
        authenticator.verify(signed)
        assertEquals(0, auditEvents.size)
    }

    @Test
    fun `signature is deterministic for same message`() {
        val unsigned = buildMessage()
        val sig1 = authenticator.sign(unsigned)
        val sig2 = authenticator.sign(unsigned)
        assertEquals(sig1, sig2)
    }

    @Test
    fun `signature differs for different payloads`() {
        val msg1 = buildMessage(payload = JsonPrimitive("a"))
        val msg2 = buildMessage(payload = JsonPrimitive("b"))
        val sig1 = authenticator.sign(msg1)
        val sig2 = authenticator.sign(msg2)
        assertFalse(sig1 == sig2)
    }

    @Test
    fun `fromSecret factory produces working authenticator`() {
        val auth = HmacAuthenticator.fromSecret("my-secret")
        val unsigned = buildMessage()
        val signed = auth.signMessage(unsigned)
        assertTrue(auth.verify(signed))
    }

    @Test
    fun `signMessage returns copy with signature set`() {
        val unsigned = buildMessage()
        val signed = authenticator.signMessage(unsigned)
        assertTrue(signed.signature.isNotEmpty())
        assertEquals(unsigned.requestId, signed.requestId)
    }

    @Test
    fun `signature is hex-encoded`() {
        val unsigned = buildMessage()
        val sig = authenticator.sign(unsigned)
        assertTrue(sig.matches(Regex("^[0-9a-f]+$")))
        // HMAC-SHA256 produces 32 bytes = 64 hex chars
        assertEquals(64, sig.length)
    }
}
