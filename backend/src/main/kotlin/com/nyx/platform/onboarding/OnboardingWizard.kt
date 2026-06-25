package com.nyx.platform.onboarding

import com.nyx.platform.billing.StripeSubscriptionManager
import com.nyx.platform.tenant.Tenant
import com.nyx.platform.tenant.TenantManager

/**
 * Guided onboarding: creates tenant, creates Stripe subscription, returns setup token.
 */
data class OnboardingResult(
    val tenant: Tenant,
    val subscriptionId: String,
    val setupToken: String,
)

class OnboardingWizard(
    private val tenantManager: TenantManager,
    private val stripe: StripeSubscriptionManager,
) {
    suspend fun onboard(
        businessName: String,
        email: String,
        phoneNumber: String,
        priceId: String = "price_starter",
    ): OnboardingResult {
        val tenant = tenantManager.create(businessName, email, phoneNumber)
        val subscription = stripe.subscribe(tenant.id, email, businessName, priceId)
        val setupToken = java.util.UUID.randomUUID().toString()
        return OnboardingResult(tenant, subscription.stripeSubscriptionId, setupToken)
    }
}
