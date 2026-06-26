package com.nyx.agent.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The original test used Mockito (`mockStatic`, mocking a final class) which is not a
 * dependency of this project and never compiled. Rewritten against the project's JUnit 5
 * stack to assert RootBridge's real behavior on a standard non-rooted JVM/CI environment,
 * where `su` is unavailable so `Runtime.exec` throws and is caught.
 */
class RootBridgeTest {

    @Test
    fun `isRooted is false when su is unavailable`() {
        assertFalse(RootBridge().isRooted())
    }

    @Test
    fun `exec returns null when not rooted`() {
        assertNull(RootBridge().exec("id"))
    }
}
