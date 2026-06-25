package com.nyx.agent.acp.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lightweight JSON-schema-style validator for ACP envelopes.
 *
 * We implement a focused subset of JSON Schema (type, required, enum,
 * minLength, min/max) sufficient to validate the ACP envelope. A full
 * JSON Schema library is intentionally avoided to keep the dependency
 * footprint small for a phone agent.
 *
 * Validation rules for every inbound message:
 *  - "version"       : string, non-empty, matches /\d+\.\d+\.\d+/
 *  - "timestamp"     : integer, > 0
 *  - "requestId"     : string, non-empty, min 1 char
 *  - "clientId"      : string, non-empty
 *  - "messageType"   : string, enum {REQUEST, RESPONSE, EVENT, HEARTBEAT}
 *  - "status"         : optional; if present enum {OK, ERROR}
 *  - "payload"        : any JSON element, non-null
 *  - "signature"      : string, non-empty, hex
 */
object AcpSchema {

    /**
     * Canonical field names.
     */
    const val VERSION = "version"
    const val TIMESTAMP = "timestamp"
    const val REQUEST_ID = "requestId"
    const val CLIENT_ID = "clientId"
    const val MESSAGE_TYPE = "messageType"
    const val STATUS = "status"
    const val PAYLOAD = "payload"
    const val SIGNATURE = "signature"

    private val REQUIRED_FIELDS = listOf(
        VERSION, TIMESTAMP, REQUEST_ID, CLIENT_ID, MESSAGE_TYPE, PAYLOAD, SIGNATURE
    )

    private val MESSAGE_TYPES = setOf("REQUEST", "RESPONSE", "EVENT", "HEARTBEAT")
    private val STATUSES = setOf("OK", "ERROR")
    private val VERSION_REGEX = Regex("""\d+\.\d+\.\d+""")
    private val HEX_REGEX = Regex("""^[0-9a-fA-F]+$""")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    /**
     * Result of validation.
     */
    sealed class ValidationResult {
        /** Valid message; the parsed envelope is available. */
        data class Valid(val message: ACPMessage) : ValidationResult()
        /** Invalid; a structured error describing the first violation. */
        data class Invalid(val error: ErrorResponse) : ValidationResult()
    }

    /**
     * Validate raw JSON text into an [ACPMessage] or a structured error.
     */
    fun validate(rawJson: String): ValidationResult {
        // Parse the JSON text.
        val element = try {
            json.parseToJsonElement(rawJson)
        } catch (e: Exception) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "Malformed JSON: ${e.message ?: "parse error"}")
            )
        }

        if (element !is JsonObject) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "Message must be a JSON object")
            )
        }

        val obj = element.jsonObject

        // Required fields present?
        for (field in REQUIRED_FIELDS) {
            if (field !in obj) {
                return ValidationResult.Invalid(
                    ErrorResponse(ErrorCode.BAD_REQUEST.code, "Missing required field: $field")
                )
            }
        }

        // version: string, matches regex
        val version = obj[VERSION]!!.jsonPrimitive.contentOrNull
            ?: return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "version must be a string")
            )
        if (version.isEmpty()) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "version must not be empty")
            )
        }
        if (!VERSION_REGEX.matches(version)) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "version must match MAJOR.MINOR.PATCH: $version")
            )
        }

        // timestamp: integer > 0
        val timestamp = obj[TIMESTAMP]!!.jsonPrimitive.intOrNull
            ?: return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "timestamp must be an integer")
            )
        if (timestamp <= 0) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "timestamp must be > 0")
            )
        }

        // requestId: non-empty string
        val requestId = obj[REQUEST_ID]!!.jsonPrimitive.contentOrNull
            ?: return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "requestId must be a string")
            )
        if (requestId.isEmpty()) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "requestId must not be empty")
            )
        }

        // clientId: non-empty string
        val clientId = obj[CLIENT_ID]!!.jsonPrimitive.contentOrNull
            ?: return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "clientId must be a string")
            )
        if (clientId.isEmpty()) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "clientId must not be empty")
            )
        }

        // messageType: enum
        val messageTypeRaw = obj[MESSAGE_TYPE]!!.jsonPrimitive.contentOrNull
            ?: return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "messageType must be a string")
            )
        if (messageTypeRaw !in MESSAGE_TYPES) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "messageType must be one of $MESSAGE_TYPES, got $messageTypeRaw")
            )
        }

        // status: optional, if present must be enum
        obj[STATUS]?.let { statusEl ->
            val statusRaw = statusEl.jsonPrimitive.contentOrNull
                ?: return ValidationResult.Invalid(
                    ErrorResponse(ErrorCode.BAD_REQUEST.code, "status must be a string")
                )
            if (statusRaw !in STATUSES) {
                return ValidationResult.Invalid(
                    ErrorResponse(ErrorCode.BAD_REQUEST.code, "status must be one of $STATUSES, got $statusRaw")
                )
            }
        }

        // payload: any non-null JSON
        val payload = obj[PAYLOAD]!!
        if (payload is JsonPrimitive && payload.contentOrNull == null) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "payload must not be null")
            )
        }

        // signature: non-empty hex string
        val signature = obj[SIGNATURE]!!.jsonPrimitive.contentOrNull
            ?: return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "signature must be a string")
            )
        if (signature.isEmpty()) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "signature must not be empty")
            )
        }
        if (!HEX_REGEX.matches(signature)) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "signature must be hex")
            )
        }

        // Decode into ACPMessage
        val decoded = try {
            json.decodeFromString(ACPMessage.serializer(), rawJson)
        } catch (e: Exception) {
            return ValidationResult.Invalid(
                ErrorResponse(ErrorCode.BAD_REQUEST.code, "Decoding failed: ${e.message ?: "unknown"}")
            )
        }
        return ValidationResult.Valid(decoded)
    }

    /**
     * Validate a parsed [ACPMessage] object directly (no raw JSON).
     */
    fun validate(message: ACPMessage): ValidationResult {
        val raw = json.encodeToString(ACPMessage.serializer(), message)
        return validate(raw)
    }
}
