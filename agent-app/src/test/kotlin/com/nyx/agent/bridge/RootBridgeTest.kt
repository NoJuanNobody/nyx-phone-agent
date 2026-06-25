package com.nyx.agent.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`

class RootBridgeTest {

    @Test
    fun `isRooted returns false when su is not available`() {
        // On a standard JVM test environment, `su` is not available.
        // RootBridge catches the exception and returns false.
        val bridge = RootBridge()
        assertFalse(bridge.isRooted())
    }

    @Test
    fun `exec returns null when isRooted is false`() {
        val bridge = object : RootBridge() {
            override fun isRooted(): Boolean = false
        }
        assertNull(bridge.exec("id"))
    }

    @Test
    fun `isRooted returns false when Runtime exec throws`() {
        // Simulates a device where su is not on PATH and throws IOException
        mockStatic(Runtime::class.java).use { mockedRuntime ->
            val runtime = mock(Runtime::class.java)
            mockedRuntime.`when`<Runtime> { Runtime.getRuntime() }.thenReturn(runtime)
            `when`(runtime.exec(arrayOf("su", "-c", "id"))).thenThrow(RuntimeException("su not found"))

            val bridge = RootBridge()
            assertFalse(bridge.isRooted())
        }
    }
}
