package com.nyx.agent.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant

/**
 * Append-only, cryptographically signed audit log for every agent action.
 *
 * Each entry is signed with a device private key (Android Keystore /
 * Titan M2-backed in production). Here the signer is abstracted so the log
 * is verifiable offline on the JVM. Entries can never be modified or deleted
 * through any public API — only [append] exists.
 */
class AuditLog(private val signer: AuditSigner) {
    private val entries: MutableList<SignedAuditEntry> = mutableListOf()
    private val lock = Any()

    fun append(entry: AuditEntry): SignedAuditEntry = synchronized(lock) {
        val signature = signer.sign(entry.canonicalBytes())
        val signed = SignedAuditEntry(entry, signature)
        entries.add(signed)
        signed
    }

    fun snapshot(): List<SignedAuditEntry> = synchronized(lock) { entries.toList() }

    /**
     * Verify every entry's signature against its content. Returns false if
     * any entry fails verification.
     */
    fun verifyAll(verifier: AuditSigner): Boolean = synchronized(lock) {
        entries.all { verifier.verify(it.entry.canonicalBytes(), it.signature) }
    }

    fun size(): Int = synchronized(lock) { entries.size }
}

/** A single audit record (unsigned). */
@Serializable
data class AuditEntry(
    @SerialName("timestamp") val timestampIso: String,
    @SerialName("action_type") val actionType: String,
    @SerialName("caller") val caller: String,
    @SerialName("decision") val decision: PolicyDecision,
    @SerialName("risk_tier") val riskTier: RiskTier,
    @SerialName("outcome") val outcome: String,
    @SerialName("detail") val detail: String? = null
) {
    fun canonicalBytes(): ByteArray =
        "$timestampIso|$actionType|$caller|$decision|$riskTier|$outcome|$detail".toByteArray()
}

@Serializable
data class SignedAuditEntry(
    @SerialName("entry") val entry: AuditEntry,
    @SerialName("signature") val signature: String
)

/** Signs and verifies audit entries with a hardware-backed key (abstracted). */
interface AuditSigner {
    fun sign(data: ByteArray): String
    fun verify(data: ByteArray, signature: String): Boolean
}

/**
 * SHA-256-with-HMAC signer for JVM testing. Production uses Android Keystore
 * (Titan M2-backed) hardware-backed keys.
 */
class HmacAuditSigner(secret: ByteArray) : AuditSigner {
    private val key = javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256")
    override fun sign(data: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }
    override fun verify(data: ByteArray, signature: String): Boolean =
        sign(data) == signature
}
