package com.nyx.agent.bridge

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CapabilityNegotiatorTest {

    private lateinit var shizuku: ShizukuBridge
    private lateinit var root: RootBridge
    private lateinit var negotiator: CapabilityNegotiator

    @BeforeEach
    fun setUp() {
        shizuku = mockk(relaxed = true)
        root = mockk(relaxed = true)
        negotiator = CapabilityNegotiator(shizuku, root)
    }

    @Test
    fun `detect returns ROOT when device is rooted`() {
        every { root.isRooted() } returns true
        every { shizuku.checkAvailable() } returns false

        assertEquals(CapabilityNegotiator.ElevationLevel.ROOT, negotiator.detect())
    }

    @Test
    fun `detect returns SHIZUKU when rooted is false but shizuku is available`() {
        every { root.isRooted() } returns false
        every { shizuku.checkAvailable() } returns true

        assertEquals(CapabilityNegotiator.ElevationLevel.SHIZUKU, negotiator.detect())
    }

    @Test
    fun `detect returns ACCESSIBILITY_ONLY when neither root nor shizuku available`() {
        every { root.isRooted() } returns false
        every { shizuku.checkAvailable() } returns false

        assertEquals(CapabilityNegotiator.ElevationLevel.ACCESSIBILITY_ONLY, negotiator.detect())
    }

    @Test
    fun `detect prefers ROOT over SHIZUKU when both available`() {
        every { root.isRooted() } returns true
        every { shizuku.checkAvailable() } returns true

        assertEquals(CapabilityNegotiator.ElevationLevel.ROOT, negotiator.detect())
    }

    @Test
    fun `exec delegates to root bridge when rooted`() {
        every { root.isRooted() } returns true
        every { root.exec("ls /data") } returns "some output"

        val result = negotiator.exec("ls /data")

        verify { root.exec("ls /data") }
        assertEquals("some output", result)
    }

    @Test
    fun `exec delegates to shizuku bridge when shizuku available and not rooted`() {
        every { root.isRooted() } returns false
        every { shizuku.checkAvailable() } returns true
        every { shizuku.exec("ls /data") } returns "shizuku output"

        val result = negotiator.exec("ls /data")

        verify { shizuku.exec("ls /data") }
        assertEquals("shizuku output", result)
    }

    @Test
    fun `exec returns null when only accessibility available`() {
        every { root.isRooted() } returns false
        every { shizuku.checkAvailable() } returns false

        val result = negotiator.exec("ls /data")

        assertNull(result)
    }
}
