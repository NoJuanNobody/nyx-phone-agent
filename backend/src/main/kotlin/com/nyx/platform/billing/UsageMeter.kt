package com.nyx.platform.billing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks minutes used per tenant per billing period.
 * Enforces plan limits; returns [UsageResult.LimitExceeded] when over limit.
 */
class UsageMeter(private val planLimits: Map<String, Int> = DEFAULT_PLAN_LIMITS) {
    private val usage = ConcurrentHashMap<String, AtomicLong>()  // tenantId → seconds used

    sealed class UsageResult {
        data class Allowed(val secondsUsed: Long) : UsageResult()
        data class LimitExceeded(val limitMinutes: Int, val usedMinutes: Long) : UsageResult()
    }

    fun record(tenantId: String, planId: String, durationSeconds: Long): UsageResult {
        val counter = usage.getOrPut(tenantId) { AtomicLong(0) }
        val total = counter.addAndGet(durationSeconds)
        val limitMinutes = planLimits[planId] ?: DEFAULT_PLAN_LIMITS["starter"]!!
        val usedMinutes = total / 60
        return if (usedMinutes <= limitMinutes) UsageResult.Allowed(total)
               else UsageResult.LimitExceeded(limitMinutes, usedMinutes)
    }

    fun getUsageSeconds(tenantId: String): Long = usage[tenantId]?.get() ?: 0L
    fun reset(tenantId: String) { usage[tenantId]?.set(0L) }

    companion object {
        val DEFAULT_PLAN_LIMITS = mapOf(
            "starter" to 100,    // 100 min/month
            "growth" to 500,     // 500 min/month
            "enterprise" to Int.MAX_VALUE,
        )
    }
}
