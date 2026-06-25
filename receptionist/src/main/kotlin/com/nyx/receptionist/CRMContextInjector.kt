package com.nyx.receptionist

/**
 * Fetches caller context from an external CRM to personalize the agent's response.
 *
 * Production implementations call HubSpot / Salesforce REST APIs.
 * The [CRMProvider] interface allows swapping providers without changing call-handling logic.
 */
interface CRMProvider {
    /** Fetch caller record by phone number. Returns null if not found. */
    suspend fun fetchCallerRecord(phoneNumber: String): CallerRecord?
}

data class CallerRecord(
    val name: String?,
    val company: String?,
    val lastInteractionSummary: String?,
    val tags: List<String> = emptyList(),
)

/**
 * Injects CRM caller data into the system prompt so the LLM can personalize the conversation.
 */
class CRMContextInjector(private val provider: CRMProvider) {
    /**
     * Build a CRM context block to append to the LLM system prompt.
     * Returns empty string if no record found.
     */
    suspend fun buildContextBlock(callerPhoneNumber: String): String {
        val record = provider.fetchCallerRecord(callerPhoneNumber) ?: return ""
        return buildString {
            appendLine("CALLER CONTEXT:")
            record.name?.let { appendLine("  Name: $it") }
            record.company?.let { appendLine("  Company: $it") }
            record.lastInteractionSummary?.let { appendLine("  Last interaction: $it") }
            if (record.tags.isNotEmpty()) appendLine("  Tags: ${record.tags.joinToString()}")
        }.trimEnd()
    }
}
