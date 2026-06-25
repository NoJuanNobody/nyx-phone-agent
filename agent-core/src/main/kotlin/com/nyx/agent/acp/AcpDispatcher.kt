package com.nyx.agent.acp

/**
 * Handles ACP commands for a given namespace.
 *
 * Implementations are registered with [AcpDispatcher] and invoked for every
 * command whose type starts with the handler's namespace.
 */
fun interface AcpHandler {
    /**
     * Handle a validated command. Must not throw — failures are returned as
     * [AcpResult] with [DispatchOutcome.FAILURE].
     */
    fun handle(command: AcpCommand): AcpResult

    companion object {
        /** Namespace this handler serves (e.g. "telephony"). */
    }
}

/**
 * Routes incoming ACP commands to the correct handler.
 *
 * Dispatch flow:
 *   1. [AcpCommandValidator] validates the command (structure + policy).
 *   2. If invalid or denied, the result is logged and returned; no handler runs.
 *   3. The handler registered for the command namespace is invoked.
 *   4. Every command — success, failure, or denial — is appended to the
 *      [AcpAuditLogger] with PII-redacted params.
 *
 * @property validator Command validator (with policy gate).
 * @property auditLogger Tamper-evident audit log.
 */
class AcpDispatcher(
    private val validator: AcpCommandValidator,
    private val auditLogger: AcpAuditLogger
) {
    private val handlers: MutableMap<String, AcpHandler> = mutableMapOf()

    /** Register a handler for a command namespace. */
    fun registerHandler(namespace: String, handler: AcpHandler) {
        require(namespace in AcpCommandValidator.KNOWN_NAMESPACES) {
            "Unknown namespace '$namespace'"
        }
        handlers[namespace] = handler
    }

    /**
     * Dispatch a command. Returns the handler result, or a structured
     * error/denial result. Never throws.
     */
    fun dispatch(command: AcpCommand): AcpResult {
        val rejection = validator.validate(command)
        if (rejection != null) {
            auditLogger.append(
                command,
                rejection.outcome,
                PiiRedactor.redact(command.params),
                rejection.error?.message
            )
            return rejection
        }
        val handler = handlers[command.namespace]
            ?: run {
                val r = AcpResult.failure(
                    com.nyx.agent.acp.schema.ErrorCode.UNAVAILABLE,
                    "No handler registered for namespace '${command.namespace}'"
                )
                auditLogger.append(command, r.outcome, PiiRedactor.redact(command.params), r.error?.message)
                return r
            }
        val result = try {
            handler.handle(command)
        } catch (t: Throwable) {
            AcpResult.failure(
                com.nyx.agent.acp.schema.ErrorCode.INTERNAL,
                "Handler threw: ${t.message}"
            )
        }
        auditLogger.append(command, result.outcome, PiiRedactor.redact(command.params), result.error?.message)
        return result
    }
}
