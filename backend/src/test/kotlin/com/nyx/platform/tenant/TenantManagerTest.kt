package com.nyx.platform.tenant

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TenantManagerTest {

    private val repo = mockk<TenantRepository>()
    private val manager = TenantManager(repo)

    @Test
    fun `create stores tenant with generated id`() = runTest {
        val slot = slot<Tenant>()
        coEvery { repo.create(capture(slot)) } answers { slot.captured }

        val tenant = manager.create("Acme Corp", "acme@example.com", "+15550001111")

        assertEquals("Acme Corp", tenant.businessName)
        assertEquals("acme@example.com", tenant.email)
        assertEquals("+15550001111", tenant.phoneNumber)
        assertEquals(TenantStatus.ACTIVE, tenant.status)
        assertEquals("starter", tenant.planId)
        assertTrue(tenant.id.isNotBlank())
        coVerify(exactly = 1) { repo.create(any()) }
    }

    @Test
    fun `create with custom planId stores correct plan`() = runTest {
        val slot = slot<Tenant>()
        coEvery { repo.create(capture(slot)) } answers { slot.captured }

        val tenant = manager.create("BigCo", "big@example.com", "+15559999999", planId = "enterprise")

        assertEquals("enterprise", tenant.planId)
    }

    @Test
    fun `create rejects blank businessName`() = runTest {
        val ex = assertThrows<IllegalArgumentException> {
            manager.create("   ", "email@example.com", "+1234567890")
        }
        assertTrue(ex.message!!.contains("businessName"))
    }

    @Test
    fun `create rejects invalid email`() = runTest {
        val ex = assertThrows<IllegalArgumentException> {
            manager.create("Acme", "notanemail", "+1234567890")
        }
        assertTrue(ex.message!!.contains("email"))
    }

    @Test
    fun `suspend sets status to SUSPENDED`() = runTest {
        val existing = Tenant("t1", "Acme", "a@b.com", "+1", TenantStatus.ACTIVE)
        coEvery { repo.findById("t1") } returns existing
        coEvery { repo.update(any()) } answers { firstArg() }

        val result = manager.suspend("t1")

        assertEquals(TenantStatus.SUSPENDED, result.status)
        coVerify { repo.update(match { it.status == TenantStatus.SUSPENDED }) }
    }

    @Test
    fun `suspend throws for unknown tenant`() = runTest {
        coEvery { repo.findById("unknown") } returns null

        assertThrows<NoSuchElementException> {
            manager.suspend("unknown")
        }
    }

    @Test
    fun `reactivate sets status to ACTIVE`() = runTest {
        val existing = Tenant("t2", "Acme", "a@b.com", "+1", TenantStatus.SUSPENDED)
        coEvery { repo.findById("t2") } returns existing
        coEvery { repo.update(any()) } answers { firstArg() }

        val result = manager.reactivate("t2")

        assertEquals(TenantStatus.ACTIVE, result.status)
    }

    @Test
    fun `reactivate throws for unknown tenant`() = runTest {
        coEvery { repo.findById("missing") } returns null

        assertThrows<NoSuchElementException> {
            manager.reactivate("missing")
        }
    }

    @Test
    fun `delete delegates to repo`() = runTest {
        coEvery { repo.delete("t3") } returns true

        val result = manager.delete("t3")

        assertTrue(result)
        coVerify { repo.delete("t3") }
    }

    @Test
    fun `get returns tenant when found`() = runTest {
        val existing = Tenant("t4", "Acme", "a@b.com", "+1")
        coEvery { repo.findById("t4") } returns existing

        val result = manager.get("t4")

        assertEquals(existing, result)
    }

    @Test
    fun `get throws for unknown tenant`() = runTest {
        coEvery { repo.findById("nope") } returns null

        assertThrows<NoSuchElementException> {
            manager.get("nope")
        }
    }
}
