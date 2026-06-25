package com.nyx.agent.policy

/**
 * Persistence layer for policy rules.
 *
 * In production this is backed by an encrypted local DB (Room + SQLCipher,
 * AES-256) synced with the Nyx cloud tenant API. For unit testing on the JVM
 * an in-memory implementation is used. The store is the only mutator of the
 * active rule set; rules loaded here are immutable snapshots handed to the
 * [PolicyEngine].
 */
interface PolicyStore {
    /** Load all active rules, ordered by descending priority. */
    fun load(): List<PolicyRule>

    /** Add or replace a rule (by id). */
    fun upsert(rule: PolicyRule)

    /** Remove a rule by id. */
    fun remove(id: String)

    /** Replace the entire rule set (used by cloud sync). */
    fun replaceAll(rules: List<PolicyRule>)
}

/**
 * In-memory [PolicyStore] suitable for tests and non-persistent runs.
 */
class InMemoryPolicyStore(initial: List<PolicyRule> = emptyList()) : PolicyStore {
    private val rules: MutableMap<String, PolicyRule> = initial.associateBy { it.id }.toMutableMap()

    override fun load(): List<PolicyRule> =
        rules.values.sortedByDescending { it.priority }

    override fun upsert(rule: PolicyRule) { rules[rule.id] = rule }

    override fun remove(id: String) { rules.remove(id) }

    override fun replaceAll(rules: List<PolicyRule>) {
        this.rules.clear()
        rules.forEach { this.rules[it.id] = it }
    }
}
