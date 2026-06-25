package com.nyx.sales

/**
 * BANT (Budget, Authority, Need, Timeline) qualification scorer.
 *
 * Scores a [LeadData] object on a 0–100 scale based on how many BANT signals are present.
 * Each present signal contributes 25 points. Used to prioritize CRM follow-up.
 */
data class BantScore(
    val budget: Boolean,
    val authority: Boolean,
    val need: Boolean,
    val timeline: Boolean,
) {
    val total: Int get() = listOf(budget, authority, need, timeline).count { it } * 25
    val isQualified: Boolean get() = total >= 50
}

class QualificationScorer {
    fun score(lead: LeadData): BantScore = BantScore(
        budget = !lead.budget.isNullOrBlank(),
        authority = !lead.name.isNullOrBlank(),
        need = lead.painPoints.isNotEmpty(),
        timeline = !lead.timeline.isNullOrBlank(),
    )
}
