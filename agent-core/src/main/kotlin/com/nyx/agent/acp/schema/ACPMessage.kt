package com.nyx.agent.acp.schema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * ACP Protocol Version.
 *
 * The protocol follows semantic versioning. Major version changes indicate
 * breaking protocol changes. Minor/patch versions are backward-compatible.
 *
 * @property major Major version — incompatible changes.
 * @property minor Minor version — backward-compatible additions.
 * @property patch Patch version — bug fixes.
 */
@Serializable
data class ProtocolVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) {
    companion object {
        /** Current protocol version supported by this implementation. */
        val CURRENT = ProtocolVersion(1, 0, 0)

        /**
         * Parse a version string like "1.0.0".
         */
        fun parse(raw: String): ProtocolVersion {
            val parts = raw.split(".")
            require(parts.size == 3) { "Version must have exactly 3 parts: $raw" }
            return ProtocolVersion(
                parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid major: ${parts[0]}"),
                parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid minor: ${parts[1]}"),
                parts[2].toIntOrNull() ?: throw IllegalArgumentException("Invalid patch: ${parts[2]}")
            )
        }
    }

    override fun toString(): String = "$major.$minor.$patch"

    /**
     * Two versions are compatible if they share the same major version.
     */
    fun isCompatibleWith(other: ProtocolVersion): Boolean = this.major == other.major
}

/**
 * Type of ACP message.
 *
 * - REQUEST: A client-initiated request expecting a RESPONSE.
 * - RESPONSE: A reply to a REQUEST.
 * - EVENT: A one-way notification pushed by the server.
 * - HEARTBEAT: A health-check ping; must be answered within 200ms.
 */
@Serializable
enum class MessageType {
    @SerialName("REQUEST") REQUEST,
    @SerialName("RESPONSE") RESPONSE,
    @SerialName("EVENT") EVENT,
    @SerialName("HEARTBEAT") HEARTBEAT
}

/**
 * Status of a RESPONSE message.
 */
@Serializable
enum class ResponseStatus {
    @SerialName("OK") OK,
    @SerialName("ERROR") ERROR
}

/**
 * Error code for structured error responses.
 */
@Serializable
enum class ErrorCode(val code: String) {
    @SerialName("ACP_400_BAD_REQUEST") BAD_REQUEST("ACP_400_BAD_REQUEST"),
    @SerialName("ACP_401_UNAUTHORIZED") UNAUTHORIZED("ACP_401_UNAUTHORIZED"),
    @SerialName("ACP_406_VERSION_MISMATCH") VERSION_MISMATCH("ACP_406_VERSION_MISMATCH"),
    @SerialName("ACP_408_TIMEOUT") TIMEOUT("ACP_408_TIMEOUT"),
    @SerialName("ACP_500_INTERNAL") INTERNAL("ACP_500_INTERNAL"),
    @SerialName("ACP_503_UNAVAILABLE") UNAVAILABLE("ACP_503_UNAVAILABLE")
}

/**
 * Structured error payload carried inside a RESPONSE with status=ERROR.
 *
 * @property code Machine-readable error code.
 * @property message Human-readable error message.
 * @property details Optional additional context.
 */
@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val details: JsonElement? = null
)

/**
 * ACP Message Envelope.
 *
 * Every message exchanged over the ACP transport is wrapped in this envelope.
 * The envelope contains routing and authentication metadata; the actual
 * request/event data lives in [payload].
 *
 * Wire format: Newline-delimited JSON. Each message is a single JSON object
 * terminated by '\n'.
 *
 * @property version Protocol version of the sender.
 * @property timestamp Epoch milliseconds (UTC).
 * @property requestId Unique identifier for the request; used to correlate RESPONSE with REQUEST.
 * @property clientId Identifies the sending client (for REQUEST/EVENT) or the server (for RESPONSE).
 * @property messageType One of REQUEST, RESPONSE, EVENT, HEARTBEAT.
 * @property status Status code — only set for RESPONSE messages.
 * @property payload The message body as a JSON element. Structure depends on messageType.
 * @property signature HMAC-SHA256 signature covering all fields except this one.
 *                  Computed over the canonical JSON of the message without the signature field.
 */
@Serializable
data class ACPMessage(
    val version: String,
    val timestamp: Long,
    val requestId: String,
    val clientId: String,
    val messageType: MessageType,
    val status: ResponseStatus? = null,
    val payload: JsonElement,
    val signature: String
) {
    /**
     * Returns the parsed protocol version.
     */
    fun protocolVersion(): ProtocolVersion = ProtocolVersion.parse(version)

    /**
     * Creates a copy of this message without the signature field,
     * for signature computation/verification.
     */
    fun withoutSignature(): ACPMessage = copy(signature = "")
}
