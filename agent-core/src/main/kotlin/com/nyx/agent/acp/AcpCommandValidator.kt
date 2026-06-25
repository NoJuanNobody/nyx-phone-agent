package com.nyx.agent.acp

import com.nyx.agent.acp.schema.ErrorCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates ACP commands before dispatch.
 *
 * A command is valid iff:
 *  - it has a non-blank `type` of the form `namespace.action`;
 *  - the namespace is one of the known namespaces;
 *  - required params per command type are present;
 *  - the policy gate (if installed) permits the command.
 *
 * On failure, a structured error describing the cause is returned.
 *
 * @param policyGate Optional predicate consulted before every command.
 *                   Returning false marks the command DENIED (never executed).
 */
class AcpCommandValidator(
    private val policyGate: ((AcpCommand) -> Boolean)? = null
) {
    /**
     * Validate a command. Returns null on success, or an [AcpResult] carrying
     * a structured error / denial on failure.
     */
    fun validate(command: AcpCommand): AcpResult? {
        // Structural checks
        if (command.type.isBlank()) {
            return AcpResult.failure(ErrorCode.BAD_REQUEST, "Command type is blank")
        }
        val ns = command.namespace
        if (ns.isEmpty() || ns !in KNOWN_NAMESPACES) {
            return AcpResult.failure(
                ErrorCode.BAD_REQUEST,
                "Unknown command namespace '$ns' in type '${command.type}'"
            )
        }
        if (command.callerId.isBlank()) {
            return AcpResult.failure(ErrorCode.BAD_REQUEST, "Command callerId is blank")
        }
        // Per-type required params
        val missing = requiredParamsFor(command.type).filter { key ->
            command.params[key] == null
        }
        if (missing.isNotEmpty()) {
            return AcpResult.failure(
                ErrorCode.BAD_REQUEST,
                "Missing required params for ${command.type}: ${missing.joinToString()}"
            )
        }
        // Policy gate
        if (policyGate != null && !policyGate(command)) {
            return AcpResult.denied("Policy Engine denied command ${command.type}")
        }
        return null
    }

    companion object {
        val KNOWN_NAMESPACES = setOf(
            AcpCommand.NS_TELEPHONY, AcpCommand.NS_MESSAGING,
            AcpCommand.NS_CALENDAR, AcpCommand.NS_SYSTEM
        )

        /** Required parameter keys per fully-qualified command type. */
        fun requiredParamsFor(type: String): Set<String> = when (type) {
            "telephony.answer", "telephony.reject", "telephony.hold" -> setOf("call_id")
            "sms.send" -> setOf("to", "body")
            "sms.read" -> setOf("limit")
            "calendar.create" -> setOf("title", "start_epoch")
            "calendar.read" -> setOf("limit")
            "system.query" -> setOf("key")
            else -> emptySet()
        }
    }
}
