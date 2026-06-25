package com.nyx.agent.acp.schema

import com.nyx.agent.acp.auth.HmacAuthenticator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/**
 * Helper for constructing ACP messages with correct signatures.
 *
 * Usage:
 *   val msg = AcpMessageBuilder(authenticator)
 *       .request("client-1", buildJsonObject { put("action", "ping") })
 *       .build()
 */
class AcpMessageBuilder(private val authenticator: HmacAuthenticator) {
    private var version: String = ProtocolVersion.CURRENT.toString()
    private var timestamp: Long = Instant.now().toEpochMilli()
    private var requestId: String = UUID.randomUUID().toString()
    private var clientId: String = ""
    private var messageType: MessageType = MessageType.REQUEST
    private var status: ResponseStatus? = null
    private var payload: JsonElement = JsonObject(emptyMap())

    fun version(v: String) = apply { version = v }
    fun timestamp(t: Long) = apply { timestamp = t }
    fun requestId(id: String) = apply { requestId = id }
    fun clientId(id: String) = apply { clientId = id }
    fun messageType(t: MessageType) = apply { messageType = t }
    fun status(s: ResponseStatus?) = apply { status = s }
    fun payload(p: JsonElement) = apply { payload = p }

    fun request(clientId: String, payload: JsonElement) = apply {
        this.messageType = MessageType.REQUEST
        this.clientId = clientId
        this.payload = payload
    }

    fun heartbeat(clientId: String) = apply {
        this.messageType = MessageType.HEARTBEAT
        this.clientId = clientId
        this.payload = buildJsonObject { put("ping", "heartbeat") }
    }

    fun event(clientId: String, payload: JsonElement) = apply {
        this.messageType = MessageType.EVENT
        this.clientId = clientId
        this.payload = payload
    }

    fun build(): ACPMessage {
        val unsigned = ACPMessage(
            version = version,
            timestamp = timestamp,
            requestId = requestId,
            clientId = clientId,
            messageType = messageType,
            status = status,
            payload = payload,
            signature = ""
        )
        return authenticator.signMessage(unsigned)
    }

    /**
     * Build the message as a JSON string (wire format).
     */
    fun buildJson(json: Json = Json { encodeDefaults = true; explicitNulls = false }): String {
        return json.encodeToString(ACPMessage.serializer(), build())
    }
}
