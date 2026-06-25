package com.nyx.receptionist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaRouterTest {

    private val salesPersona = PersonaConfig(
        id = "sales",
        name = "Sales Assistant",
        greeting = "Hello! How can I help you with sales today?",
        intentKeywords = listOf("buy", "purchase", "pricing", "quote"),
    )

    private val supportPersona = PersonaConfig(
        id = "support",
        name = "Support Agent",
        greeting = "Hello! I'm here to help with technical support.",
        intentKeywords = listOf("help", "broken", "issue", "problem", "support"),
    )

    private val billingPersona = PersonaConfig(
        id = "billing",
        name = "Billing Agent",
        greeting = "Hello! I can assist with billing questions.",
        intentKeywords = listOf("invoice", "billing", "payment", "charge"),
    )

    @Test
    fun `keyword match routes to correct persona`() {
        val router = PersonaRouter(listOf(salesPersona, supportPersona, billingPersona))
        val result = router.route("I want to buy your product")
        assertTrue(result is RoutingResult.Routed)
        val routed = result as RoutingResult.Routed
        assertEquals("sales", routed.persona.id)
        assertEquals("buy", routed.matchedKeyword)
    }

    @Test
    fun `keyword match is case insensitive`() {
        val router = PersonaRouter(listOf(salesPersona, supportPersona))
        val result = router.route("I need HELP with my account")
        assertTrue(result is RoutingResult.Routed)
        val routed = result as RoutingResult.Routed
        assertEquals("support", routed.persona.id)
        assertEquals("help", routed.matchedKeyword)
    }

    @Test
    fun `first keyword match wins when transcript matches multiple personas`() {
        val router = PersonaRouter(listOf(salesPersona, billingPersona, supportPersona))
        // "invoice" matches billing; "support" also present but billing is checked first
        val result = router.route("I have an invoice issue and need support")
        assertTrue(result is RoutingResult.Routed)
        // billingPersona is second in list; salesPersona has no match; billingPersona "issue" -> no wait
        // salesPersona keywords: buy, purchase, pricing, quote — none match
        // billingPersona keywords: invoice, billing, payment, charge — "invoice" matches
        val routed = result as RoutingResult.Routed
        assertEquals("billing", routed.persona.id)
        assertEquals("invoice", routed.matchedKeyword)
    }

    @Test
    fun `no keyword match falls back to first persona when defaultToFirst is true`() {
        val router = PersonaRouter(listOf(salesPersona, supportPersona), defaultToFirst = true)
        val result = router.route("I just wanted to say hello")
        assertTrue(result is RoutingResult.Routed)
        val routed = result as RoutingResult.Routed
        assertEquals("sales", routed.persona.id)
        assertNull(routed.matchedKeyword)
    }

    @Test
    fun `no keyword match returns NoMatch when defaultToFirst is false`() {
        val router = PersonaRouter(listOf(salesPersona, supportPersona), defaultToFirst = false)
        val result = router.route("I just wanted to say hello")
        assertTrue(result is RoutingResult.NoMatch)
    }

    @Test
    fun `empty personas list returns NoMatch`() {
        val router = PersonaRouter(emptyList(), defaultToFirst = true)
        val result = router.route("I want to buy something")
        assertTrue(result is RoutingResult.NoMatch)
    }
}
