package com.nyx.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify

class CapabilityNegotiatorTest {

    private lateinit var shizuku: ShizukuBridge
    private lateinit var root: RootBridge
    private lateinit var negotiator: CapabilityNegotiator

    @Before
    fun setUp() {
        shizuku = mock(ShizukuBridge::class.java)
        root = mock(RootBridge::class.java)
        negotiator = CapabilityNegotiator(shizuku, root)
    }

    @Test
    fun `detect returns ROOT when device is rooted`() {
        `when`(root.isRooted()).thenReturn(true)
        `when`(shizuku.checkAvailable()).thenReturn(false)

        assertEquals(CapabilityNegotiator.ElevationLevel.ROOT, negotiator.detect())
    }

    @Test
    fun `detect returns SHIZUKU when rooted is false but shizuku is available`() {
        `when`(root.isRooted()).thenReturn(false)
        `when`(shizuku.checkAvailable()).thenReturn(true)

        assertEquals(CapabilityNegotiator.ElevationLevel.SHIZUKU, negotiator.detect())
    }

    @Test
    fun `detect returns ACCESSIBILITY_ONLY when neither root nor shizuku available`() {
        `when`(root.isRooted()).thenReturn(false)
        `when`(shizuku.checkAvailable()).thenReturn(false)

        assertEquals(CapabilityNegotiator.ElevationLevel.ACCESSIBILITY_ONLY, negotiator.detect())
    }

    @Test
    fun `detect prefers ROOT over SHIZUKU when both available`() {
        `when`(root.isRooted()).thenReturn(true)
        `when`(shizuku.checkAvailable()).thenReturn(true)

        assertEquals(CapabilityNegotiator.ElevationLevel.ROOT, negotiator.detect())
    }

    @Test
    fun `exec delegates to root bridge when rooted`() {
        `when`(root.isRooted()).thenReturn(true)
        `when`(root.exec("ls /data")).thenReturn("some output")

        val result = negotiator.exec("ls /data")

        verify(root).exec("ls /data")
        assertEquals("some output", result)
    }

    @Test
    fun `exec delegates to shizuku bridge when shizuku available and not rooted`() {
        `when`(root.isRooted()).thenReturn(false)
        `when`(shizuku.checkAvailable()).thenReturn(true)
        `when`(shizuku.exec("ls /data")).thenReturn("shizuku output")

        val result = negotiator.exec("ls /data")

        verify(shizuku).exec("ls /data")
        assertEquals("shizuku output", result)
    }

    @Test
    fun `exec returns null when only accessibility available`() {
        `when`(root.isRooted()).thenReturn(false)
        `when`(shizuku.checkAvailable()).thenReturn(false)

        val result = negotiator.exec("ls /data")

        assertNull(result)
    }
}
