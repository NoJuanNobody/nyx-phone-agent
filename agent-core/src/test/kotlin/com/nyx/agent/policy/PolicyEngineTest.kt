package com.nyx.agent.policy

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Policy Engine")
class PolicyEngineTest {

    private fun engine(rules: List<PolicyRule> = emptyList()): PolicyEngine =
        PolicyEngine(InMemoryPolicyStore(rules))

    @Test
    fun `low risk action is allowed by default`() {
        val e = engine()
        val r = e.evaluate("sms.read")
        assertEquals(PolicyDecision.ALLOW, r.decision)
        assertEquals(RiskTier.LOW, r.riskTier)
    }

    @Test
    fun `high risk action requires confirm by default`() {
        val e = engine()
        val r = e.evaluate("sms.send")
        assertEquals(PolicyDecision.REQUIRE_CONFIRM, r.decision)
        assertEquals(RiskTier.HIGH, r.riskTier)
    }

    @Test
    fun `explicit deny rule overrides risk tier`() {
        val e = engine(listOf(PolicyRule("deny-sms", "sms.send", PolicyDecision.DENY, priority = 10)))
        val r = e.evaluate("sms.send")
        assertEquals(PolicyDecision.DENY, r.decision)
        assertEquals("deny-sms", r.matchedRuleId)
    }

    @Test
    fun `higher priority rule wins`() {
        val e = engine(listOf(
            PolicyRule("allow-sms", "sms.send", PolicyDecision.ALLOW, priority = 1),
            PolicyRule("deny-sms", "sms.send", PolicyDecision.DENY, priority = 10)
        ))
        assertEquals(PolicyDecision.DENY, e.evaluate("sms.send").decision)
    }

    @Test
    fun `business hours context predicate`() {
        val e = engine(listOf(
            PolicyRule("biz-only", "sms.send", PolicyDecision.ALLOW, priority = 5, contextPredicateKind = "business_hours")
        ))
        // outside hours -> no rule matches -> default confirm
        assertEquals(PolicyDecision.REQUIRE_CONFIRM, e.evaluate("sms.send", mapOf("hour" to "23")).decision)
        // within hours -> rule matches -> allow
        assertEquals(PolicyDecision.ALLOW, e.evaluate("sms.send", mapOf("hour" to "12")).decision)
    }

    @Test
    fun `classifier knows at least 20 actions`() {
        assertTrue(RiskClassifier.knownActions.size >= 20)
    }

    @Test
    fun `unknown action classified high fail-safe`() {
        assertEquals(RiskTier.HIGH, RiskClassifier.classify("nuclear.launch"))
    }

    @Test
    fun `consent gate denies on timeout`() = runBlocking {
        val gate = ConsentGate(ConfirmationAdapter { _, _ -> kotlinx.coroutines.delay(60_000); false })
        assertFalse(gate.confirm("sms.send", "send?", timeoutMs = 100L))
    }

    @Test
    fun `audit log signs and verifies`() {
        val signer = HmacAuditSigner("secret".toByteArray())
        val log = AuditLog(signer)
        log.append(AuditEntry("2026-01-01T00:00:00Z", "sms.send", "llm", PolicyDecision.ALLOW, RiskTier.HIGH, "ok"))
        assertEquals(1, log.size())
        assertTrue(log.verifyAll(signer))
    }

    @Test
    fun `store replaceAll replaces rules`() {
        val store = InMemoryPolicyStore()
        store.replaceAll(listOf(PolicyRule("a", "sms.read", PolicyDecision.DENY)))
        assertEquals(1, store.load().size)
        store.replaceAll(emptyList())
        assertTrue(store.load().isEmpty())
    }
}
