package com.nyx.agent.acp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Tamper-evident, append-only audit log for every ACP command.
 *
 * Each entry is chained to the previous one via a SHA-256 hash
 * (entry_n.prevHash = SHA256(entry_{n-1})). This makes retroactive
 * modification detectable: changing any field invalidates every
 * subsequent hash.
 *
 * Entries are never deleted or mutated through any public API.
 *
 * @property entries Append-only backing list. Exposed read-only via [snapshot].
 */
class AcpAuditLogger {
    private val entries: MutableList<AuditEntry> = mutableListOf()
    private val counter: AtomicLong = AtomicLong(0)
    private val lock = Any()

    /**
     * Append a new audit entry and return it.
     *
     * @param command The command that was executed.
     * @param outcome The dispatch outcome.
     * @param redactedParams PII-redacted copy of the command params.
     * @param detail Optional human-readable detail.
     */
    fun append(
        command: AcpCommand,
        outcome: DispatchOutcome,
        redactedParams: JsonObject,
        detail: String? = null
    ): AuditEntry = synchronized(lock) {
        val seq = counter.incrementAndGet()
        val prev = entries.lastOrNull()?.hash ?: GENESIS_HASH
        val entry = AuditEntry(
            seq = seq,
            timestampIso = Instant.now().toString(),
            commandType = command.type,
            callerId = command.callerId,
            requestId = command.requestId,
            redactedParams = redactedParams,
            outcome = outcome,
            detail = detail,
            prevHash = prev
        ).withHash()
        entries.add(entry)
        entry
    }

    /** Read-only snapshot of the full audit trail. */
    fun snapshot(): List<AuditEntry> = synchronized(lock) { entries.toList() }

    /** Number of entries recorded. */
    fun size(): Int = synchronized(lock) { entries.size }

    /**
     * Verify the integrity of the chain. Returns false if any entry's
     * computed hash does not match its stored hash, or if a prevHash
     * link is broken.
     */
    fun verifyChain(): Boolean = synchronized(lock) {
        var prev = GENESIS_HASH
        for (e in entries) {
            if (e.prevHash != prev) return false
            if (e.hash != e.computeHash()) return false
            prev = e.hash
        }
        true
    }

    companion object {
        const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}

/**
 * A single immutable audit entry.
 */
@Serializable
data class AuditEntry(
    @SerialName("seq") val seq: Long,
    @SerialName("timestamp") val timestampIso: String,
    @SerialName("command_type") val commandType: String,
    @SerialName("caller_id") val callerId: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("params") val redactedParams: JsonObject,
    @SerialName("outcome") val outcome: DispatchOutcome,
    @SerialName("detail") val detail: String? = null,
    @SerialName("prev_hash") val prevHash: String,
    @SerialName("hash") val hash: String = ""
) {
    /**
     * Canonical content over which the hash is computed (excludes [hash]).
     */
    fun canonicalContent(): String =
        "$seq|$timestampIso|$commandType|$callerId|$requestId|$redactedParams|$outcome|$detail|$prevHash"

    /** Compute the SHA-256 hash of this entry's canonical content. */
    fun computeHash(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(canonicalContent().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Return a copy with the hash field populated. */
    fun withHash(): AuditEntry = copy(hash = computeHash())
}

/**
 * Redacts known-PII fields from a command's params before logging.
 */
object PiiRedactor {
    private val sensitiveKeys = setOf(
        "to", "from", "recipient", "sender", "phone", "number", "body", "message",
        "content", "address", "email"
    )

    fun redact(params: JsonObject): JsonObject = buildJsonObject {
        params.forEach { (k, v) ->
            if (k.lowercase() in sensitiveKeys) {
                put(k, "***REDACTED***")
            } else {
                put(k, v)
            }
        }
    }
}
