package com.nyx.agent.acp

import com.nyx.agent.acp.schema.ErrorCode
import com.nyx.agent.acp.schema.ErrorResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ACP Command — the structured payload of a REQUEST message.
 *
 * Every agent action flows through ACP as a command. The command carries
 * the action type, caller identity, and parameters. Parameters that are
 * PII (phone numbers, message bodies) are redacted in the audit log.
 *
 * @property type The command type — e.g. `telephony.answer`, `sms.send`.
 * @property callerId Identity of the caller (client id or `llm`/`system`).
 * @property params Command parameters as a JSON object.
 * @property requestId Correlates with the ACP envelope requestId.
 */
@Serializable
data class AcpCommand(
    @SerialName("type") val type: String,
    @SerialName("caller_id") val callerId: String,
    @SerialName("params") val params: JsonObject,
    @SerialName("request_id") val requestId: String
) {
    companion object {
        /** Namespaces for command types. */
        const val NS_TELEPHONY = "telephony"
        const val NS_MESSAGING = "sms"
        const val NS_CALENDAR = "calendar"
        const val NS_SYSTEM = "system"

        /** Build a command from loosely-typed params. */
        fun of(type: String, callerId: String, requestId: String, params: Map<String, JsonElement>): AcpCommand =
            AcpCommand(type, callerId, JsonObject(params), requestId)
    }

    /** Namespace prefix (the part before the first dot). */
    val namespace: String get() = type.substringBefore('.', "")
}

/**
 * Outcome of dispatching a command.
 */
@Serializable
enum class DispatchOutcome {
    @SerialName("success") SUCCESS,
    @SerialName("denied") DENIED,
    @SerialName("failure") FAILURE
}

/**
 * Result returned by a handler and by the dispatcher.
 */
@Serializable
data class AcpResult(
    val outcome: DispatchOutcome,
    val data: JsonObject = JsonObject(emptyMap()),
    val error: ErrorResponse? = null
) {
    companion object {
        fun success(data: Map<String, JsonElement> = emptyMap()) =
            AcpResult(DispatchOutcome.SUCCESS, buildJsonObject { data.forEach { (k, v) -> put(k, v) } })
        fun denied(reason: String) =
            AcpResult(DispatchOutcome.DENIED, error = ErrorResponse(ErrorCode.UNAUTHORIZED.code, reason))
        fun failure(code: ErrorCode = ErrorCode.INTERNAL, reason: String) =
            AcpResult(DispatchOutcome.FAILURE, error = ErrorResponse(code.code, reason))
    }
}
