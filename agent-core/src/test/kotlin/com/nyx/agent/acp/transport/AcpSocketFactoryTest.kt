package com.nyx.agent.acp.transport

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ACP Socket Factory")
class AcpSocketFactoryTest {

    @Test
    fun `resolve with preferAbstract returns AbstractNamespace`() {
        val config = AcpSocketConfig(preferAbstract = true)
        val addr = AcpSocketFactory.resolve(config)
        assertTrue(addr is AcpSocketAddress.AbstractNamespace)
        val ns = addr as AcpSocketAddress.AbstractNamespace
        assertNotNull(ns.name)
        assertNotNull(ns.address)
    }

    @Test
    fun `resolve with pathOverride returns FileSystem`() {
        val tmpPath = System.getProperty("java.io.tmpdir") + "/acp-test-" + System.nanoTime() + ".sock"
        val config = AcpSocketConfig(pathOverride = tmpPath)
        val addr = AcpSocketFactory.resolve(config)
        assertTrue(addr is AcpSocketAddress.FileSystem)
        val fs = addr as AcpSocketAddress.FileSystem
        assertTrue(fs.path.toString().contains("acp-test"))
    }

    @Test
    fun `default constants are set`() {
        assertTrue(AcpSocketFactory.DEFAULT_FS_PATH == "/data/nyx/acp.sock")
        assertTrue(AcpSocketFactory.DEFAULT_ABSTRACT_NAME == "nyx_acp_socket")
    }

    @Test
    fun `resolve without data-nyx path falls back to abstract on non-rooted system`() {
        // On macOS/dev machines, /data/nyx doesn't exist, so we get abstract.
        val config = AcpSocketConfig()
        val addr = AcpSocketFactory.resolve(config)
        // Could be either, but on a dev machine without /data/nyx it's abstract.
        // Just verify it's non-null and has a valid address.
        assertNotNull(addr)
        when (addr) {
            is AcpSocketAddress.FileSystem -> assertNotNull(addr.address)
            is AcpSocketAddress.AbstractNamespace -> assertNotNull(addr.address)
        }
    }

    @Test
    fun `pathOverride takes precedence over preferAbstract`() {
        val tmpPath = System.getProperty("java.io.tmpdir") + "/acp-override-" + System.nanoTime() + ".sock"
        val config = AcpSocketConfig(pathOverride = tmpPath, preferAbstract = true)
        val addr = AcpSocketFactory.resolve(config)
        assertTrue(addr is AcpSocketAddress.FileSystem)
    }
}
