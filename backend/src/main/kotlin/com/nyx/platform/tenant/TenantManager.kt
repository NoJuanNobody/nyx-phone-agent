package com.nyx.platform.tenant

import java.util.UUID

/**
 * Tenant lifecycle management: create, suspend, reactivate, delete.
 * Coordinates with [TenantRepository] and emits lifecycle events.
 */
class TenantManager(private val repo: TenantRepository) {
    suspend fun create(businessName: String, email: String, phoneNumber: String, planId: String = "starter"): Tenant {
        require(businessName.isNotBlank()) { "businessName must not be blank" }
        require(email.contains('@')) { "invalid email" }
        val tenant = Tenant(
            id = UUID.randomUUID().toString(),
            businessName = businessName,
            email = email,
            phoneNumber = phoneNumber,
            planId = planId,
        )
        return repo.create(tenant)
    }

    suspend fun suspend(id: String): Tenant {
        val tenant = repo.findById(id) ?: throw NoSuchElementException("Tenant $id not found")
        return repo.update(tenant.copy(status = TenantStatus.SUSPENDED))
    }

    suspend fun reactivate(id: String): Tenant {
        val tenant = repo.findById(id) ?: throw NoSuchElementException("Tenant $id not found")
        return repo.update(tenant.copy(status = TenantStatus.ACTIVE))
    }

    suspend fun delete(id: String): Boolean = repo.delete(id)

    suspend fun get(id: String): Tenant = repo.findById(id) ?: throw NoSuchElementException("Tenant $id not found")
}
