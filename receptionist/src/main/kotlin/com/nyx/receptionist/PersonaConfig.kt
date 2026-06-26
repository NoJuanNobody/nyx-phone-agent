package com.nyx.receptionist

/**
 * Configuration for a Nyx AI receptionist persona.
 *
 * Each business configures one or more personas that handle different caller intents.
 * The [PersonaRouter] selects the active persona based on STT-detected intent.
 */
data class PersonaConfig(
    val id: String,
    val name: String,
    val greeting: String,
    val voiceId: String = "default",
    val intentKeywords: List<String> = emptyList(),
    val escalationPhone: String? = null,
    val systemPromptSuffix: String = "",
)

/**
 * Outcome of routing a caller to a persona.
 */
sealed class RoutingResult {
    data class Routed(val persona: PersonaConfig, val matchedKeyword: String?) : RoutingResult()
    data class Escalated(val toPhone: String, val reason: String) : RoutingResult()
    object NoMatch : RoutingResult()
}
