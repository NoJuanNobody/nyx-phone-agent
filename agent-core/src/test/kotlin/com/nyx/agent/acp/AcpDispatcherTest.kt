package com.nyx.agent.acp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ACP Dispatcher / Validator / Audit")
class AcpDispatcherTest {

    private fun cmd(type: String, caller: String = "llm", reqId: String = "r1", params: Map<String, JsonPrimitive> = emptyMap()): AcpCommand =
        AcpCommand(type, caller, buildJsonObject { params.forEach { (k, v) -> put(k, v) } }, reqId)

    @Test
    fun `validator rejects blank type`() {
        val v = AcpCommandValidator()
        val r = v.validate(cmd(""))!!
        assertEquals(DispatchOutcome.FAILURE, r.outcome)
    }

    @Test
    fun `validator rejects unknown namespace`() {
        val v = AcpCommandValidator()
        val r = v.validate(cmd("bogus.action"))
        assertNotNull(r)
        assertEquals(DispatchOutcome.FAILURE, r!!.outcome)
    }

    @Test
    fun `validator rejects missing required params`() {
        val v = AcpCommandValidator()
        val r = v.validate(cmd("sms.send")) // missing to, body
        assertNotNull(r)
        assertTrue(r!!.error!!.message.contains("Missing required params"))
    }

    @Test
    fun `validator accepts valid command`() {
        val v = AcpCommandValidator()
        assertNull(v.validate(cmd("sms.send", params = mapOf("to" to JsonPrimitive("555"), "body" to JsonPrimitive("hi")))))
    }

    @Test
    fun `policy gate denies command`() {
        val v = AcpCommandValidator(policyGate = { false })
        val r = v.validate(cmd("sms.send", params = mapOf("to" to JsonPrimitive("555"), "body" to JsonPrimitive("hi"))))!!
        assertEquals(DispatchOutcome.DENIED, r.outcome)
    }

    @Test
    fun `dispatcher routes to handler and audits success`() {
        val audit = AcpAuditLogger()
        val v = AcpCommandValidator()
        val d = AcpDispatcher(v, audit)
        d.registerHandler("sms") { AcpResult.success(mapOf("sent" to JsonPrimitive(true))) }

        val result = d.dispatch(cmd("sms.send", params = mapOf("to" to JsonPrimitive("5551234"), "body" to JsonPrimitive("hello"))))
        assertEquals(DispatchOutcome.SUCCESS, result.outcome)

        val entry = audit.snapshot().single()
        assertEquals("sms.send", entry.commandType)
        assertEquals(DispatchOutcome.SUCCESS, entry.outcome)
        // PII redacted
        assertEquals("***REDACTED***", entry.redactedParams["to"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dispatcher returns denial when handler missing`() {
        val audit = AcpAuditLogger()
        val d = AcpDispatcher(AcpCommandValidator(), audit)
        val r = d.dispatch(cmd("sms.send", params = mapOf("to" to JsonPrimitive("555"), "body" to JsonPrimitive("x"))))
        assertEquals(DispatchOutcome.FAILURE, r.outcome)
        assertEquals(1, audit.size())
    }

    @Test
    fun `dispatcher catches handler exceptions`() {
        val audit = AcpAuditLogger()
        val d = AcpDispatcher(AcpCommandValidator(), audit)
        d.registerHandler("sms") { error("boom") }
        val r = d.dispatch(cmd("sms.send", params = mapOf("to" to JsonPrimitive("555"), "body" to JsonPrimitive("x"))))
        assertEquals(DispatchOutcome.FAILURE, r.outcome)
        assertTrue(r.error!!.message.contains("boom"))
    }

    @Test
    fun `audit chain is tamper evident`() {
        val audit = AcpAuditLogger()
        val cmd = cmd("sms.read", params = mapOf("limit" to JsonPrimitive(5)))
        audit.append(cmd, DispatchOutcome.SUCCESS, PiiRedactor.redact(cmd.params))
        audit.append(cmd, DispatchOutcome.SUCCESS, PiiRedactor.redact(cmd.params))
        assertTrue(audit.verifyChain())
        assertEquals(2, audit.size())
    }

    @Test
    fun `redactor redacts known pii keys`() {
        val redacted = PiiRedactor.redact(
            buildJsonObject {
                put("to", "555")
                put("limit", 5)
            }
        )
        assertEquals("***REDACTED***", redacted["to"]!!.jsonPrimitive.content)
        assertEquals("5", redacted["limit"]!!.jsonPrimitive.content)
    }
}
