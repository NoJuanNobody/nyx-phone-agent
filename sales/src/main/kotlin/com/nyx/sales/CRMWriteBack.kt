package com.nyx.sales

/**
 * Writes extracted lead data back to a CRM (HubSpot / Salesforce).
 * The [CRMClient] interface allows swapping providers.
 */
data class CRMContact(val id: String, val email: String?, val name: String?)
data class CRMDeal(val id: String, val contactId: String, val title: String, val stage: String)

interface CRMClient {
    suspend fun upsertContact(lead: LeadData): CRMContact
    suspend fun createDeal(contact: CRMContact, lead: LeadData, score: BantScore): CRMDeal
    suspend fun addNote(dealId: String, noteBody: String)
}

class CRMWriteBack(private val client: CRMClient) {
    /**
     * Write a complete lead to the CRM: upsert contact, create deal, add call note.
     * Returns the created [CRMDeal].
     */
    suspend fun writeLead(lead: LeadData, transcript: String, score: BantScore): CRMDeal {
        val contact = client.upsertContact(lead)
        val deal = client.createDeal(contact, lead, score)
        val note = buildString {
            appendLine("Call transcript summary:")
            appendLine("BANT score: ${score.total}/100 (qualified=${score.isQualified})")
            if (lead.painPoints.isNotEmpty()) appendLine("Pain points: ${lead.painPoints.joinToString()}")
            if (lead.nextSteps.isNotEmpty()) appendLine("Next steps: ${lead.nextSteps.joinToString()}")
            appendLine("---")
            append(transcript.take(2000))  // truncate very long transcripts
        }
        client.addNote(deal.id, note)
        return deal
    }
}
