package com.nyx.platform.tenant

import java.time.Instant

enum class TenantStatus { ACTIVE, SUSPENDED, DELETED }

data class Tenant(
    val id: String,
    val businessName: String,
    val email: String,
    val phoneNumber: String,
    val status: TenantStatus = TenantStatus.ACTIVE,
    val planId: String = "starter",
    val createdAt: Instant = Instant.now(),
)
