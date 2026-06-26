package com.nyx.sales

/**
 * Fires a webhook to Zapier / Make.com / n8n to trigger automated follow-up sequences
 * (email, SMS, calendar invite) after a qualified call.
 */
data class FollowUpPayload(
    val dealId: String,
    val leadName: String?,
    val leadEmail: String?,
    val bantScore: Int,
    val nextSteps: List<String>,
    val webhookTag: String,
)

interface WebhookClient {
    suspend fun post(url: String, payload: FollowUpPayload): Boolean
}

class FollowUpTrigger(
    private val webhookUrl: String,
    private val client: WebhookClient,
) {
    /**
     * Fire a follow-up webhook if the lead is qualified (BANT >= 50).
     * Returns false if skipped due to low qualification score.
     */
    suspend fun trigger(deal: CRMDeal, lead: LeadData, score: BantScore): Boolean {
        if (!score.isQualified) return false
        val payload = FollowUpPayload(
            dealId = deal.id,
            leadName = lead.name,
            leadEmail = lead.email,
            bantScore = score.total,
            nextSteps = lead.nextSteps,
            webhookTag = "post_call_followup",
        )
        return client.post(webhookUrl, payload)
    }
}
