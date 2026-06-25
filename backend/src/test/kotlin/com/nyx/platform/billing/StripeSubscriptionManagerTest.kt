package com.nyx.platform.billing

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StripeSubscriptionManagerTest {

    private val client = mockk<StripeClient>()
    private val stripeManager = StripeSubscriptionManager(client)

    @Test
    fun `subscribe creates customer then subscription`() = runTest {
        coEvery { client.createCustomer("acme@example.com", "Acme Corp") } returns "cus_123"
        coEvery { client.createSubscription("cus_123", "price_starter") } returns "sub_456"

        val subscription = stripeManager.subscribe("tenant-1", "acme@example.com", "Acme Corp", "price_starter")

        assertEquals("tenant-1", subscription.tenantId)
        assertEquals("price_starter", subscription.planId)
        assertEquals("active", subscription.status)
        assertEquals("cus_123", subscription.stripeCustomerId)
        assertEquals("sub_456", subscription.stripeSubscriptionId)
        assertEquals("tenant-1-sub_456", subscription.id)

        coVerify(exactly = 1) { client.createCustomer("acme@example.com", "Acme Corp") }
        coVerify(exactly = 1) { client.createSubscription("cus_123", "price_starter") }
    }

    @Test
    fun `cancel delegates to client cancelSubscription`() = runTest {
        val subscription = Subscription(
            id = "tenant-1-sub_456",
            tenantId = "tenant-1",
            planId = "price_starter",
            status = "active",
            stripeCustomerId = "cus_123",
            stripeSubscriptionId = "sub_456",
        )
        coEvery { client.cancelSubscription("sub_456") } returns true

        val result = stripeManager.cancel(subscription)

        assertTrue(result)
        coVerify(exactly = 1) { client.cancelSubscription("sub_456") }
    }

    @Test
    fun `upgrade delegates to client updateSubscription with new price`() = runTest {
        val subscription = Subscription(
            id = "tenant-1-sub_456",
            tenantId = "tenant-1",
            planId = "price_starter",
            status = "active",
            stripeCustomerId = "cus_123",
            stripeSubscriptionId = "sub_456",
        )
        coEvery { client.updateSubscription("sub_456", "price_growth") } returns true

        val result = stripeManager.upgrade(subscription, "price_growth")

        assertTrue(result)
        coVerify(exactly = 1) { client.updateSubscription("sub_456", "price_growth") }
    }
}
