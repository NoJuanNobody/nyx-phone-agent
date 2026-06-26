package com.nyx.platform.billing

/**
 * Manages Stripe subscriptions for each tenant.
 *
 * Production implementation uses the Stripe Java SDK.
 * [StripeClient] is an interface so tests can mock it.
 */
data class Subscription(
    val id: String,
    val tenantId: String,
    val planId: String,
    val status: String,
    val stripeCustomerId: String,
    val stripeSubscriptionId: String,
)

interface StripeClient {
    suspend fun createCustomer(email: String, name: String): String  // returns stripeCustomerId
    suspend fun createSubscription(customerId: String, priceId: String): String  // returns stripeSubscriptionId
    suspend fun cancelSubscription(subscriptionId: String): Boolean
    suspend fun updateSubscription(subscriptionId: String, newPriceId: String): Boolean
}

class StripeSubscriptionManager(private val client: StripeClient) {
    suspend fun subscribe(tenantId: String, email: String, name: String, priceId: String): Subscription {
        val customerId = client.createCustomer(email, name)
        val subscriptionId = client.createSubscription(customerId, priceId)
        return Subscription(
            id = "$tenantId-$subscriptionId",
            tenantId = tenantId,
            planId = priceId,
            status = "active",
            stripeCustomerId = customerId,
            stripeSubscriptionId = subscriptionId,
        )
    }

    suspend fun cancel(subscription: Subscription): Boolean =
        client.cancelSubscription(subscription.stripeSubscriptionId)

    suspend fun upgrade(subscription: Subscription, newPriceId: String): Boolean =
        client.updateSubscription(subscription.stripeSubscriptionId, newPriceId)
}
