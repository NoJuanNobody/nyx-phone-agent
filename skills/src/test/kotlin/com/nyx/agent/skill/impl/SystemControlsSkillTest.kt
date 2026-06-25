package com.nyx.agent.skill.impl

import com.nyx.agent.skill.SkillResult
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SystemControlsSkillTest {

    private lateinit var bridge: SystemControlsBridge
    private lateinit var skill: SystemControlsSkill

    @Before
    fun setUp() {
        bridge = mockk()
        skill = SystemControlsSkill(bridge)
    }

    // ── Wi-Fi ─────────────────────────────────────────────────────────────────

    @Test
    fun `wifi get returns current state`() = runTest {
        every { bridge.getWifiEnabled() } returns true

        val result = skill.execute(mapOf("control" to "wifi", "action" to "get"))

        assertTrue(result is SkillResult.Success)
        assertEquals(true, (result as SkillResult.Success).output["wifi_enabled"])
    }

    @Test
    fun `wifi set true reports before and after`() = runTest {
        every { bridge.getWifiEnabled() } returns false
        every { bridge.setWifiEnabled(true) } just runs

        val result = skill.execute(mapOf("control" to "wifi", "action" to "set", "enabled" to true))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(false, output["before"])
        assertEquals(true, output["after"])
        verify { bridge.setWifiEnabled(true) }
    }

    @Test
    fun `wifi toggle flips state from false to true`() = runTest {
        every { bridge.getWifiEnabled() } returns false
        every { bridge.setWifiEnabled(true) } just runs

        val result = skill.execute(mapOf("control" to "wifi", "action" to "toggle"))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(false, output["before"])
        assertEquals(true, output["after"])
        verify { bridge.setWifiEnabled(true) }
    }

    @Test
    fun `wifi toggle flips state from true to false`() = runTest {
        every { bridge.getWifiEnabled() } returns true
        every { bridge.setWifiEnabled(false) } just runs

        val result = skill.execute(mapOf("control" to "wifi", "action" to "toggle"))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(true, output["before"])
        assertEquals(false, output["after"])
        verify { bridge.setWifiEnabled(false) }
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────

    @Test
    fun `bluetooth toggle flips state from false to true`() = runTest {
        every { bridge.getBluetoothEnabled() } returns false
        every { bridge.setBluetoothEnabled(true) } just runs

        val result = skill.execute(mapOf("control" to "bluetooth", "action" to "toggle"))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(false, output["before"])
        assertEquals(true, output["after"])
        verify { bridge.setBluetoothEnabled(true) }
    }

    @Test
    fun `bluetooth toggle flips state from true to false`() = runTest {
        every { bridge.getBluetoothEnabled() } returns true
        every { bridge.setBluetoothEnabled(false) } just runs

        val result = skill.execute(mapOf("control" to "bluetooth", "action" to "toggle"))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(true, output["before"])
        assertEquals(false, output["after"])
        verify { bridge.setBluetoothEnabled(false) }
    }

    // ── Volume ────────────────────────────────────────────────────────────────

    @Test
    fun `volume set clamps value within 0-100`() = runTest {
        every { bridge.getVolume("media") } returns 50
        every { bridge.setVolume("media", any()) } just runs

        // Pass 150 — should be clamped to 100
        val result = skill.execute(
            mapOf("control" to "volume", "action" to "set", "stream" to "media", "level" to 150)
        )

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(50, output["before"])
        assertEquals(150, output["after"])  // reported as-requested; bridge receives clamped value
        verify { bridge.setVolume("media", 100) }
    }

    @Test
    fun `volume set clamps negative value to 0`() = runTest {
        every { bridge.getVolume("ringer") } returns 30
        every { bridge.setVolume("ringer", any()) } just runs

        val result = skill.execute(
            mapOf("control" to "volume", "action" to "set", "stream" to "ringer", "level" to -10)
        )

        assertTrue(result is SkillResult.Success)
        verify { bridge.setVolume("ringer", 0) }
    }

    @Test
    fun `volume mute sets stream to 0`() = runTest {
        every { bridge.getVolume("media") } returns 75
        every { bridge.setVolume("media", 0) } just runs

        val result = skill.execute(mapOf("control" to "volume", "action" to "mute", "stream" to "media"))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(75, output["before"])
        assertEquals(0, output["after"])
        verify { bridge.setVolume("media", 0) }
    }

    @Test
    fun `missing level for volume set returns Failure`() = runTest {
        every { bridge.getVolume("media") } returns 50

        val result = skill.execute(mapOf("control" to "volume", "action" to "set"))

        assertTrue(result is SkillResult.Failure)
        val error = (result as SkillResult.Failure).error
        assertTrue(error.contains("level"))
    }

    // ── Brightness ────────────────────────────────────────────────────────────

    @Test
    fun `brightness set clamps within 0-255`() = runTest {
        every { bridge.getBrightness() } returns 128
        every { bridge.setBrightness(any()) } just runs

        // Pass 300 — should be clamped to 255
        val result = skill.execute(mapOf("control" to "brightness", "action" to "set", "level" to 300))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(128, output["before"])
        assertEquals(300, output["after"])  // reported as-requested; bridge receives clamped value
        verify { bridge.setBrightness(255) }
    }

    @Test
    fun `brightness set clamps negative value to 0`() = runTest {
        every { bridge.getBrightness() } returns 100
        every { bridge.setBrightness(any()) } just runs

        val result = skill.execute(mapOf("control" to "brightness", "action" to "set", "level" to -5))

        assertTrue(result is SkillResult.Success)
        verify { bridge.setBrightness(0) }
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    fun `unknown control returns Failure`() = runTest {
        val result = skill.execute(mapOf("control" to "thermostat"))

        assertTrue(result is SkillResult.Failure)
        val error = (result as SkillResult.Failure).error
        assertTrue(error.contains("thermostat"))
    }

    @Test
    fun `missing control returns Failure with helpful message`() = runTest {
        val result = skill.execute(emptyMap())

        assertTrue(result is SkillResult.Failure)
        val error = (result as SkillResult.Failure).error
        assertTrue(error.contains("control"))
    }

    @Test
    fun `missing level for brightness set returns Failure`() = runTest {
        every { bridge.getBrightness() } returns 128

        val result = skill.execute(mapOf("control" to "brightness", "action" to "set"))

        assertTrue(result is SkillResult.Failure)
        val error = (result as SkillResult.Failure).error
        assertTrue(error.contains("level"))
    }
}
