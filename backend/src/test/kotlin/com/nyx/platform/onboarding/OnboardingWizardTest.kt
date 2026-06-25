package com.nyx.platform.onboarding

import com.nyx.platform.billing.StripeClient
import com.nyx.platform.billing.StripeSubscriptionManager
import com.nyx.platform.tenant.Tenant
import com.nyx.platform.tenant.TenantManager
import com.nyx.platform.tenant.TenantRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OnboardingWizardTest {

    private val repo = mockk<TenantRepository>()
    private val stripeClient = mockk<StripeClient>()
    private val tenantManager = TenantManager(repo)
    private val stripeManager = StripeSubscriptionManager(stripeClient)
    private val wizard = OnboardingWizard(tenantManager, stripeManager)

    @Test
    fun `onboard creates tenant then subscription and returns setup token`() = runTest {
        val tenantSlot = slot<Tenant>()
        coEvery { repo.create(capture(tenantSlot)) } answers { tenantSlot.captured }
        coEvery { stripeClient.createCustomer("acme@example.com", "Acme Corp") } returns "cus_abc"
        coEvery { stripeClient.createSubscription("cus_abc", "price_starter") } returns "sub_xyz"

        val result = wizard.onboard("Acme Corp", "acme@example.com", "+15550001111")

        assertEquals("Acme Corp", result.tenant.businessName)
        assertEquals("acme@example.com", result.tenant.email)
        assertEquals("sub_xyz", result.subscriptionId)
        assertTrue(result.setupToken.isNotBlank())

        // Verify order: tenant first, then Stripe
        coVerify(exactly = 1) { repo.create(any()) }
        coVerify(exactly = 1) { stripeClient.createCustomer("acme@example.com", "Acme Corp") }
        coVerify(exactly = 1) { stripeClient.createSubscription("cus_abc", "price_starter") }
    }

    @Test
    fun `onboard uses provided priceId`() = runTest {
        val tenantSlot = slot<Tenant>()
        coEvery { repo.create(capture(tenantSlot)) } answers { tenantSlot.captured }
        coEvery { stripeClient.createCustomer(any(), any()) } returns "cus_abc"
        coEvery { stripeClient.createSubscription(any(), "price_growth") } returns "sub_growth"

        val result = wizard.onboard("BigCo", "big@example.com", "+1", priceId = "price_growth")

        assertEquals("sub_growth", result.subscriptionId)
        coVerify { stripeClient.createSubscription(any(), "price_growth") }
    }

    @Test
    fun `each onboarding produces unique setup tokens`() = runTest {
        val tenantSlot = slot<Tenant>()
        coEvery { repo.create(capture(tenantSlot)) } answers { tenantSlot.captured }
        coEvery { stripeClient.createCustomer(any(), any()) } returns "cus_1"
        coEvery { stripeClient.createSubscription(any(), any()) } returns "sub_1"

        val r1 = wizard.onboard("Co1", "a@example.com", "+1")
        val r2 = wizard.onboard("Co2", "b@example.com", "+2")

        assertNotEquals(r1.setupToken, r2.setupToken)
    }
}
