package com.nyx.agent.skill.impl

import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

data class SmsThread(
    val contactName: String?,
    val phoneNumber: String,
    val messages: List<SmsMessage>,
)

data class SmsMessage(
    val body: String,
    val isIncoming: Boolean,
    val timestampMs: Long,
)

/**
 * Abstraction over Android SMS and Contacts ContentProviders for testability.
 */
interface SmsBridge {
    /** Read the N most recent SMS threads. */
    fun readThreads(limit: Int): List<SmsThread>
    /** Send an SMS. Throws on failure (no SIM, invalid number, etc.). */
    fun sendSms(toNumber: String, body: String)
    /** Resolve a display name for a phone number, or null if not in contacts. */
    fun resolveContact(phoneNumber: String): String?
}

/**
 * Skill for reading and sending SMS messages.
 *
 * MANDATORY: sending SMS always requires user confirmation ([requiresConfirmation] = true).
 * The [GuardedSkillRouter] enforces this before [execute] is called.
 *
 * Args for "read":
 * - `action`: "read"
 * - `limit` (Int, default 10): number of threads to return
 *
 * Args for "send":
 * - `action`: "send"
 * - `to` (String): phone number or contact name
 * - `body` (String): message text
 */
class SmsSkill(private val bridge: SmsBridge) : Skill {
    override val name = "sms"
    override val description = "Read recent SMS threads or send an SMS (confirmation required for send)"
    override val requiredPermissions = listOf(
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CONTACTS",
    )
    override val requiresConfirmation = true  // enforced for send; list is read-only but declared for safety

    override suspend fun execute(args: Map<String, Any>): SkillResult {
        return when (val action = args["action"] as? String) {
            "read" -> {
                val limit = (args["limit"] as? Number)?.toInt() ?: 10
                val threads = bridge.readThreads(limit)
                SkillResult.Success(mapOf(
                    "thread_count" to threads.size,
                    "threads" to threads.map { t ->
                        mapOf(
                            "contact" to (t.contactName ?: t.phoneNumber),
                            "message_count" to t.messages.size,
                            "last_message" to t.messages.lastOrNull()?.body,
                        )
                    }
                ))
            }
            "send" -> {
                val to = args["to"] as? String
                    ?: return SkillResult.Failure("'to' (phone number or contact name) is required for send")
                val body = args["body"] as? String
                    ?: return SkillResult.Failure("'body' (message text) is required for send")
                return try {
                    bridge.sendSms(to, body)
                    SkillResult.Success(mapOf("sent_to" to to, "body_length" to body.length))
                } catch (e: Exception) {
                    SkillResult.Failure("Failed to send SMS: ${e.message}", e)
                }
            }
            else -> SkillResult.Failure("Unknown action '$action'. Use 'read' or 'send'")
        }
    }
}
