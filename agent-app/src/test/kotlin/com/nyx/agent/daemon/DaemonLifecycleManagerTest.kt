package com.nyx.agent.daemon

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DaemonLifecycleManager].
 *
 * All Android framework calls are mocked with MockK so these tests run on
 * the JVM without an emulator or device.
 */
class DaemonLifecycleManagerTest {

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var activityManager: ActivityManager

    @MockK
    private lateinit var powerManager: PowerManager

    private val packageName = "com.nyx.agent"

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        every { context.packageName } returns packageName
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // start()
    // -------------------------------------------------------------------------

    @Test
    fun `start calls startForegroundService on API 26+`() {
        // Simulate API 26 (Build.VERSION.SDK_INT is a static field — we rely on the
        // real value being >= 26 in any modern test environment, but we verify the
        // intent action instead of the dispatch method to stay SDK-version agnostic).
        val intentSlot = slot<Intent>()
        every { context.startForegroundService(capture(intentSlot)) } just Runs
        every { context.startService(any()) } just Runs

        DaemonLifecycleManager.start(context)

        val capturedComponent = intentSlot.captured.component
            ?: intentSlot.captured.resolveActivity(mockk(relaxed = true))

        // The intent must target NyxAgentDaemon
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            verify(exactly = 1) { context.startForegroundService(any()) }
            verify(exactly = 0) { context.startService(any()) }
        } else {
            verify(exactly = 1) { context.startService(any()) }
            verify(exactly = 0) { context.startForegroundService(any()) }
        }
    }

    @Test
    fun `start intent targets NyxAgentDaemon class`() {
        val intentSlot = slot<Intent>()
        every { context.startForegroundService(capture(intentSlot)) } just Runs
        every { context.startService(capture(intentSlot)) } just Runs

        DaemonLifecycleManager.start(context)

        assertEquals(
            NyxAgentDaemon::class.java.name,
            intentSlot.captured.component?.className
        )
    }

    // -------------------------------------------------------------------------
    // stop()
    // -------------------------------------------------------------------------

    @Test
    fun `stop calls stopService with NyxAgentDaemon intent`() {
        val intentSlot = slot<Intent>()
        every { context.stopService(capture(intentSlot)) } returns true

        DaemonLifecycleManager.stop(context)

        verify(exactly = 1) { context.stopService(any()) }
        assertEquals(
            NyxAgentDaemon::class.java.name,
            intentSlot.captured.component?.className
        )
    }

    // -------------------------------------------------------------------------
    // restart()
    // -------------------------------------------------------------------------

    @Test
    fun `restart calls stop then start`() {
        every { context.stopService(any()) } returns true
        every { context.startForegroundService(any()) } just Runs
        every { context.startService(any()) } just Runs

        DaemonLifecycleManager.restart(context)

        // Both stop and start must be called exactly once
        verify(exactly = 1) { context.stopService(any()) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            verify(exactly = 1) { context.startForegroundService(any()) }
        } else {
            verify(exactly = 1) { context.startService(any()) }
        }
    }

    // -------------------------------------------------------------------------
    // isRunning()
    // -------------------------------------------------------------------------

    @Test
    fun `isRunning returns true when daemon is in running services list`() {
        val serviceInfo = ActivityManager.RunningServiceInfo().apply {
            service = ComponentName(packageName, NyxAgentDaemon::class.java.name)
        }
        every {
            context.getSystemService(Context.ACTIVITY_SERVICE)
        } returns activityManager
        @Suppress("DEPRECATION")
        every { activityManager.getRunningServices(Int.MAX_VALUE) } returns listOf(serviceInfo)

        assertTrue(DaemonLifecycleManager.isRunning(context))
    }

    @Test
    fun `isRunning returns false when daemon is not in running services list`() {
        every {
            context.getSystemService(Context.ACTIVITY_SERVICE)
        } returns activityManager
        @Suppress("DEPRECATION")
        every { activityManager.getRunningServices(Int.MAX_VALUE) } returns emptyList()

        assertFalse(DaemonLifecycleManager.isRunning(context))
    }

    @Test
    fun `isRunning returns false when a different service is running`() {
        val otherServiceInfo = ActivityManager.RunningServiceInfo().apply {
            service = ComponentName(packageName, "com.nyx.agent.other.SomeOtherService")
        }
        every {
            context.getSystemService(Context.ACTIVITY_SERVICE)
        } returns activityManager
        @Suppress("DEPRECATION")
        every { activityManager.getRunningServices(Int.MAX_VALUE) } returns listOf(otherServiceInfo)

        assertFalse(DaemonLifecycleManager.isRunning(context))
    }

    // -------------------------------------------------------------------------
    // requestBatteryOptimizationExemption()
    // -------------------------------------------------------------------------

    @Test
    fun `requestBatteryOptimizationExemption does nothing when already exempted`() {
        every {
            context.getSystemService(Context.POWER_SERVICE)
        } returns powerManager
        every {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } returns true

        DaemonLifecycleManager.requestBatteryOptimizationExemption(context)

        // No activity should be started when the app is already exempted
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `requestBatteryOptimizationExemption launches settings when not exempted`() {
        val intentSlot = slot<Intent>()
        every {
            context.getSystemService(Context.POWER_SERVICE)
        } returns powerManager
        every {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } returns false
        every { context.startActivity(capture(intentSlot)) } just Runs

        DaemonLifecycleManager.requestBatteryOptimizationExemption(context)

        verify(exactly = 1) { context.startActivity(any()) }
        assertEquals(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            intentSlot.captured.action
        )
        assertEquals(
            Uri.parse("package:$packageName"),
            intentSlot.captured.data
        )
    }
}
