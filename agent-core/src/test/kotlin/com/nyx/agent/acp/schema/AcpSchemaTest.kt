package com.nyx.agent.acp.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DisplayName

@DisplayName("ACP Schema Validation")
class AcpSchemaTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private fun validMessageJson(
        version: String = "1.0.0",
        timestamp: Long = System.currentTimeMillis(),
        requestId: String = "req-1",
        clientId: String = "client-1",
        messageType: String = "REQUEST",
        status: String? = null,
        payload: String = """{"action":"ping"}""",
        signature: String = "aabbccdd"
    ): String {
        val statusPart = if (status != null) ""","status":"$status"""" else ""
        return """{"version":"$version","timestamp":$timestamp,"requestId":"$requestId","clientId":"$clientId","messageType":"$messageType"$statusPart,"payload":$payload,"signature":"$signature"}"""
    }

    @Test
    fun `valid message passes validation`() {
        val raw = validMessageJson()
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Valid)
        val msg = (result as AcpSchema.ValidationResult.Valid).message
        assertEquals("1.0.0", msg.version)
        assertEquals("req-1", msg.requestId)
        assertEquals(MessageType.REQUEST, msg.messageType)
    }

    @Test
    fun `malformed JSON returns 400 error`() {
        val result = AcpSchema.validate("""{bad json""")
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
        val err = (result as AcpSchema.ValidationResult.Invalid).error
        assertEquals("ACP_400_BAD_REQUEST", err.code)
        assertTrue(err.message.contains("Malformed JSON"))
    }

    @Test
    fun `non-object JSON returns 400 error`() {
        val result = AcpSchema.validate(""""just a string"""")
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
        val err = (result as AcpSchema.ValidationResult.Invalid).error
        assertEquals("ACP_400_BAD_REQUEST", err.code)
    }

    @Test
    fun `missing required field returns 400 error`() {
        // Missing signature
        val raw = """{"version":"1.0.0","timestamp":123,"requestId":"r","clientId":"c","messageType":"REQUEST","payload":{}}"""
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
        val err = (result as AcpSchema.ValidationResult.Invalid).error
        assertEquals("ACP_400_BAD_REQUEST", err.code)
        assertTrue(err.message.contains("Missing required field: signature"))
    }

    @Test
    fun `invalid version format returns 400 error`() {
        val raw = validMessageJson(version = "1.0")
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
        val err = (result as AcpSchema.ValidationResult.Invalid).error
        assertTrue(err.message.contains("version must match"))
    }

    @Test
    fun `non-numeric timestamp returns 400 error`() {
        val raw = """{"version":"1.0.0","timestamp":"notanumber","requestId":"r","clientId":"c","messageType":"REQUEST","payload":{},"signature":"aa"}"""
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
    }

    @Test
    fun `zero or negative timestamp returns 400 error`() {
        val raw = validMessageJson(timestamp = 0)
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
        assertTrue((result as AcpSchema.ValidationResult.Invalid).error.message.contains("timestamp"))
    }

    @Test
    fun `empty requestId returns 400 error`() {
        val raw = validMessageJson(requestId = "")
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
    }

    @Test
    fun `empty clientId returns 400 error`() {
        val raw = validMessageJson(clientId = "")
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
    }

    @Test
    fun `invalid messageType returns 400 error`() {
        val raw = validMessageJson(messageType = "INVALID")
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
        assertTrue((result as AcpSchema.ValidationResult.Invalid).error.message.contains("messageType"))
    }

    @Test
    fun `invalid status returns 400 error`() {
        val raw = validMessageJson(status = "BAD")
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
    }

    @Test
    fun `valid status OK passes`() {
        val raw = validMessageJson(messageType = "RESPONSE", status = "OK")
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Valid)
    }

    @Test
    fun `non-hex signature returns 400 error`() {
        val raw = validMessageJson(signature = "not-hex-zzz")
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
        assertTrue((result as AcpSchema.ValidationResult.Invalid).error.message.contains("hex"))
    }

    @Test
    fun `empty signature returns 400 error`() {
        val raw = validMessageJson(signature = "")
        val result = AcpSchema.validate(raw)
        assertTrue(result is AcpSchema.ValidationResult.Invalid)
    }

    @TestFactory
    fun `all message types are accepted`(): List<DynamicTest> {
        return listOf("REQUEST", "RESPONSE", "EVENT", "HEARTBEAT").map { type ->
            DynamicTest.dynamicTest("messageType=$type is valid") {
                val raw = validMessageJson(messageType = type, status = if (type == "RESPONSE") "OK" else null)
                val result = AcpSchema.validate(raw)
                assertTrue(result is AcpSchema.ValidationResult.Valid, "Expected valid for $type")
            }
        }
    }
}
