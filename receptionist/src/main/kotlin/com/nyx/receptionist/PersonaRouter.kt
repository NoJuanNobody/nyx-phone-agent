package com.nyx.receptionist

/**
 * Routes a caller's detected intent to the matching [PersonaConfig].
 *
 * Matching strategy: scan caller STT transcript for each persona's [PersonaConfig.intentKeywords]
 * (case-insensitive). First match wins. Falls back to the default persona (first in list)
 * if no keyword matches and [defaultToFirst] = true.
 */
class PersonaRouter(
    private val personas: List<PersonaConfig>,
    private val defaultToFirst: Boolean = true,
) {
    fun route(transcript: String): RoutingResult {
        for (persona in personas) {
            for (kw in persona.intentKeywords) {
                if (transcript.contains(kw, ignoreCase = true)) {
                    return RoutingResult.Routed(persona, kw)
                }
            }
        }
        return if (defaultToFirst && personas.isNotEmpty())
            RoutingResult.Routed(personas.first(), null)
        else
            RoutingResult.NoMatch
    }
}
