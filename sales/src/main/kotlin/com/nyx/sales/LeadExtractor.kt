package com.nyx.sales

/**
 * Extracts structured sales intelligence from a call transcript using LLM post-processing.
 *
 * In production: sends transcript to Claude API with a structured extraction prompt.
 * In tests: accepts a [TranscriptParser] abstraction.
 */
data class LeadData(
    val name: String?,
    val email: String?,
    val company: String?,
    val budget: String?,
    val timeline: String?,
    val painPoints: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
)

interface TranscriptParser {
    suspend fun extractLead(transcript: String): LeadData
}

class LeadExtractor(private val parser: TranscriptParser) {
    suspend fun extract(transcript: String): LeadData = parser.extractLead(transcript)
}
