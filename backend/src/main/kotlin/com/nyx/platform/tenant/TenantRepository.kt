package com.nyx.platform.tenant

/**
 * Persistence abstraction for [Tenant] records.
 * Production implementation uses PostgreSQL via JDBC/Exposed.
 */
interface TenantRepository {
    suspend fun create(tenant: Tenant): Tenant
    suspend fun findById(id: String): Tenant?
    suspend fun findByEmail(email: String): Tenant?
    suspend fun update(tenant: Tenant): Tenant
    suspend fun delete(id: String): Boolean
    suspend fun listAll(): List<Tenant>
}
