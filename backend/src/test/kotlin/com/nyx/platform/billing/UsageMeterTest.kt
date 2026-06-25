package com.nyx.platform.billing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UsageMeterTest {

    private val meter = UsageMeter()

    @Test
    fun `record increments usage and returns Allowed when under limit`() {
        val result = meter.record("t1", "starter", 60L)  // 1 minute

        assertTrue(result is UsageMeter.UsageResult.Allowed)
        assertEquals(60L, (result as UsageMeter.UsageResult.Allowed).secondsUsed)
        assertEquals(60L, meter.getUsageSeconds("t1"))
    }

    @Test
    fun `record accumulates across multiple calls`() {
        meter.record("t2", "starter", 30L)
        meter.record("t2", "starter", 30L)
        val result = meter.record("t2", "starter", 60L)

        assertTrue(result is UsageMeter.UsageResult.Allowed)
        assertEquals(120L, meter.getUsageSeconds("t2"))
    }

    @Test
    fun `record returns LimitExceeded when over plan limit`() {
        // starter = 100 min = 6000 seconds
        meter.record("t3", "starter", 5999L)  // 99 min 59 sec → still within 99 minutes used
        val result = meter.record("t3", "starter", 61L)  // pushes past 100 minutes

        assertTrue(result is UsageMeter.UsageResult.LimitExceeded) {
            "Expected LimitExceeded but got $result"
        }
        val exceeded = result as UsageMeter.UsageResult.LimitExceeded
        assertEquals(100, exceeded.limitMinutes)
        assertTrue(exceeded.usedMinutes > 100)
    }

    @Test
    fun `reset zeroes usage for tenant`() {
        meter.record("t4", "starter", 3600L)
        assertEquals(3600L, meter.getUsageSeconds("t4"))

        meter.reset("t4")

        assertEquals(0L, meter.getUsageSeconds("t4"))
    }

    @Test
    fun `getUsageSeconds returns 0 for unknown tenant`() {
        assertEquals(0L, meter.getUsageSeconds("never-seen"))
    }

    @Test
    fun `record is concurrent-safe`() = runBlocking {
        val tenantId = "concurrent-tenant"
        val jobs = (1..100).map {
            launch(Dispatchers.Default) {
                meter.record(tenantId, "starter", 1L)
            }
        }
        jobs.forEach { it.join() }

        assertEquals(100L, meter.getUsageSeconds(tenantId))
    }

    @Test
    fun `enterprise plan has no effective limit`() {
        val result = meter.record("enterprise-tenant", "enterprise", Long.MAX_VALUE / 2)

        assertTrue(result is UsageMeter.UsageResult.Allowed)
    }

    @Test
    fun `growth plan allows 500 minutes`() {
        // 499 minutes = 29940 seconds → still allowed
        val result = meter.record("growth-tenant", "growth", 29940L)
        assertTrue(result is UsageMeter.UsageResult.Allowed)
    }

    @Test
    fun `unknown plan falls back to starter limit`() {
        // starter limit is 100 min; exceed it
        meter.record("t5", "unknown-plan", 6000L)  // exactly 100 min
        val result = meter.record("t5", "unknown-plan", 60L)  // over

        assertTrue(result is UsageMeter.UsageResult.LimitExceeded)
    }
}
