package com.nyx.sales

/**
 * Orchestrates the full post-call sales pipeline:
 *   transcript → LeadExtractor → QualificationScorer → CRMWriteBack → FollowUpTrigger
 */
class PostCallPipeline(
    private val extractor: LeadExtractor,
    private val scorer: QualificationScorer,
    private val crm: CRMWriteBack,
    private val followUp: FollowUpTrigger,
) {
    data class PipelineResult(
        val lead: LeadData,
        val score: BantScore,
        val deal: CRMDeal,
        val followUpFired: Boolean,
    )

    suspend fun run(transcript: String): PipelineResult {
        val lead = extractor.extract(transcript)
        val score = scorer.score(lead)
        val deal = crm.writeLead(lead, transcript, score)
        val followUpFired = followUp.trigger(deal, lead, score)
        return PipelineResult(lead, score, deal, followUpFired)
    }
}
