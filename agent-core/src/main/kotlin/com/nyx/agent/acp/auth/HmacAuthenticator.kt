package com.nyx.agent.acp.auth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * HMAC-SHA256 message signing and verification.
 *
 * Every ACP message must carry an HMAC-SHA256 signature over the canonical
 * JSON representation of the message (all envelope fields except `signature`).
 * The signature is computed as:
 *
 *   HMAC_SHA256(secret, canonicalJson(message_without_signature))
 *
 * Verification MUST happen before any payload is processed. On failure, a
 * security audit event is emitted via [securityAuditSink] (if configured).
 *
 * @property secret The shared secret key bytes.
 * @param securityAuditSink Called with a [SecurityAuditEvent] when a signature
 *          verification fails.
 */
class HmacAuthenticator(
    secret: ByteArray,
    private val securityAuditSink: ((SecurityAuditEvent) -> Unit)? = null
) {
    private val secretKey = SecretKeySpec(secret, "HmacSHA256")
    private val mac: ThreadLocal<Mac> = ThreadLocal.withInitial {
        Mac.getInstance("HmacSHA256").also { it.init(secretKey) }
    }

    /**
     * Canonical JSON encoder used for signing/verification.
     * - Sorted keys (alphabetical)
     * - No pretty printing
     * - No unknown keys ignored (explicit)
     */
    private val canonicalJson = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    /**
     * Compute the HMAC-SHA256 hex signature for the given message, excluding
     * the `signature` field.
     */
    fun sign(message: com.nyx.agent.acp.schema.ACPMessage): String {
        val canonical = canonicalJsonWithoutSignature(message)
        val raw = mac.get().doFinal(canonical.toByteArray(Charsets.UTF_8))
        return raw.toHex()
    }

    /**
     * Verify that the message's `signature` field matches the computed
     * HMAC-SHA256 signature.
     *
     * On failure, emits a [SecurityAuditEvent] to the configured sink.
     *
     * @return true if the signature is valid, false otherwise.
     */
    fun verify(message: com.nyx.agent.acp.schema.ACPMessage): Boolean {
        val expected = sign(message)
        val actual = message.signature
        val valid = constantTimeEquals(expected, actual)
        if (!valid) {
            securityAuditSink?.invoke(
                SecurityAuditEvent(
                    timestamp = Instant.now().toEpochMilli(),
                    requestId = message.requestId,
                    clientId = message.clientId,
                    reason = "HMAC signature mismatch"
                )
            )
        }
        return valid
    }

    /**
     * Sign a message in-place: returns a copy with the `signature` field set.
     */
    fun signMessage(message: com.nyx.agent.acp.schema.ACPMessage): com.nyx.agent.acp.schema.ACPMessage {
        val sig = sign(message)
        return message.copy(signature = sig)
    }

    /**
     * Canonical JSON of the message without the signature field.
     * Uses sorted keys for deterministic output.
     */
    private fun canonicalJsonWithoutSignature(message: com.nyx.agent.acp.schema.ACPMessage): String {
        val unsigned = message.copy(signature = "")
        // Build a sorted-key JSON object manually for determinism.
        val sortedObj = buildJsonObject {
            put("clientId", unsigned.clientId)
            unsigned.status?.let { put("status", it.name) }
            put("messageType", unsigned.messageType.name)
            put("payload", unsigned.payload)
            put("requestId", unsigned.requestId)
            put("signature", "")
            put("timestamp", unsigned.timestamp)
            put("version", unsigned.version)
        }
        // Re-serialize with sorted keys.
        return canonicalJson.encodeToString(JsonObject.serializer(), sortedObj)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * Create an authenticator from a string secret.
         */
        fun fromSecret(
            secret: String,
            securityAuditSink: ((SecurityAuditEvent) -> Unit)? = null
        ): HmacAuthenticator = HmacAuthenticator(secret.toByteArray(Charsets.UTF_8), securityAuditSink)
    }
}

/**
 * A security audit event emitted when HMAC verification fails.
 */
data class SecurityAuditEvent(
    val timestamp: Long,
    val requestId: String,
    val clientId: String,
    val reason: String
)
