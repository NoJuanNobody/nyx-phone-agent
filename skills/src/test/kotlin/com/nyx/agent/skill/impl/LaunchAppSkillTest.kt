package com.nyx.agent.skill.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.nyx.agent.skill.SkillResult
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LaunchAppSkill].
 *
 * Common packages exercised:
 * - `com.google.android.gm`  (Gmail)
 * - `com.spotify.music`      (Spotify)
 * - `com.android.chrome`     (Chrome)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchAppSkillTest {

    // -----------------------------------------------------------------------
    // Test fixtures
    // -----------------------------------------------------------------------

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var skill: LaunchAppSkill

    /** A canned launcher [Intent] returned by the mock [PackageManager]. */
    private val fakeLaunchIntent = mockk<Intent>(relaxed = true)

    @Before
    fun setUp() {
        packageManager = mockk()
        context = mockk()

        every { context.packageManager } returns packageManager
        // startActivity is a Unit-returning call — allow it by default
        justRun { context.startActivity(any()) }
        // fakeLaunchIntent.addFlags returns itself so the chain compiles
        every { fakeLaunchIntent.addFlags(any()) } returns fakeLaunchIntent

        skill = LaunchAppSkill(context)
    }

    // -----------------------------------------------------------------------
    // Launch by exact package name
    // -----------------------------------------------------------------------

    @Test
    fun `launch by exact package name succeeds and returns launched_package`() = runTest {
        // Arrange — Gmail package
        val pkg = "com.google.android.gm"
        every { packageManager.getLaunchIntentForPackage(pkg) } returns fakeLaunchIntent

        // Act
        val result = skill.execute(mapOf("package_name" to pkg))

        // Assert
        assertTrue(result is SkillResult.Success)
        assertEquals(pkg, (result as SkillResult.Success).output["launched_package"])
        verify { context.startActivity(fakeLaunchIntent) }
    }

    @Test
    fun `launch by exact package name — Spotify — succeeds`() = runTest {
        val pkg = "com.spotify.music"
        every { packageManager.getLaunchIntentForPackage(pkg) } returns fakeLaunchIntent

        val result = skill.execute(mapOf("package_name" to pkg))

        assertTrue(result is SkillResult.Success)
        assertEquals(pkg, (result as SkillResult.Success).output["launched_package"])
    }

    @Test
    fun `launch by exact package name — Chrome — succeeds`() = runTest {
        val pkg = "com.android.chrome"
        every { packageManager.getLaunchIntentForPackage(pkg) } returns fakeLaunchIntent

        val result = skill.execute(mapOf("package_name" to pkg))

        assertTrue(result is SkillResult.Success)
        assertEquals(pkg, (result as SkillResult.Success).output["launched_package"])
    }

    @Test
    fun `launch by unknown package name returns Failure`() = runTest {
        val pkg = "com.example.doesnotexist"
        every { packageManager.getLaunchIntentForPackage(pkg) } returns null

        val result = skill.execute(mapOf("package_name" to pkg))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains(pkg))
    }

    @Test
    fun `launch by package name propagates ActivityNotFoundException as Failure`() = runTest {
        val pkg = "com.google.android.gm"
        every { packageManager.getLaunchIntentForPackage(pkg) } returns fakeLaunchIntent
        every { context.startActivity(any()) } throws ActivityNotFoundException("no activity")

        val result = skill.execute(mapOf("package_name" to pkg))

        assertTrue(result is SkillResult.Failure)
        assertCauseIsInstanceOf<ActivityNotFoundException>(result as SkillResult.Failure)
    }

    // -----------------------------------------------------------------------
    // Launch by fuzzy app name
    // -----------------------------------------------------------------------

    @Test
    fun `launch by fuzzy app name finds match and returns Success`() = runTest {
        val pkg = "com.google.android.gm"
        val appInfo = ApplicationInfo().apply { packageName = pkg }

        @Suppress("DEPRECATION")
        every { packageManager.getInstalledApplications(PackageManager.GET_META_DATA) } returns
            listOf(appInfo)
        every { packageManager.getApplicationLabel(appInfo) } returns "Gmail"
        every { packageManager.getLaunchIntentForPackage(pkg) } returns fakeLaunchIntent

        val result = skill.execute(mapOf("app_name" to "gmail"))

        assertTrue(result is SkillResult.Success)
        assertEquals(pkg, (result as SkillResult.Success).output["launched_package"])
    }

    @Test
    fun `launch by fuzzy app name — case-insensitive partial match`() = runTest {
        val pkg = "com.spotify.music"
        val appInfo = ApplicationInfo().apply { packageName = pkg }

        @Suppress("DEPRECATION")
        every { packageManager.getInstalledApplications(PackageManager.GET_META_DATA) } returns
            listOf(appInfo)
        every { packageManager.getApplicationLabel(appInfo) } returns "Spotify: Music and Podcasts"
        every { packageManager.getLaunchIntentForPackage(pkg) } returns fakeLaunchIntent

        val result = skill.execute(mapOf("app_name" to "Spotify"))

        assertTrue(result is SkillResult.Success)
        assertEquals(pkg, (result as SkillResult.Success).output["launched_package"])
    }

    @Test
    fun `launch by unknown app name returns Failure`() = runTest {
        val appInfo = ApplicationInfo().apply { packageName = "com.example.other" }

        @Suppress("DEPRECATION")
        every { packageManager.getInstalledApplications(PackageManager.GET_META_DATA) } returns
            listOf(appInfo)
        every { packageManager.getApplicationLabel(appInfo) } returns "Other App"

        val result = skill.execute(mapOf("app_name" to "nonexistent_xyz"))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("nonexistent_xyz"))
    }

    // -----------------------------------------------------------------------
    // Launch by deep link URI
    // -----------------------------------------------------------------------

    @Test
    fun `launch with deep_link fires ACTION_VIEW intent`() = runTest {
        val uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
        val intentSlot = slot<Intent>()
        justRun { context.startActivity(capture(intentSlot)) }

        val result = skill.execute(mapOf("deep_link" to uri))

        assertTrue(result is SkillResult.Success)
        assertEquals(uri, (result as SkillResult.Success).output["launched_deep_link"])
        assertEquals(Intent.ACTION_VIEW, intentSlot.captured.action)
        assertEquals(uri, intentSlot.captured.data?.toString())
    }

    @Test
    fun `launch with unresolvable deep_link returns Failure`() = runTest {
        val uri = "unknownscheme://no-handler"
        every { context.startActivity(any()) } throws ActivityNotFoundException("no handler")

        val result = skill.execute(mapOf("deep_link" to uri))

        assertTrue(result is SkillResult.Failure)
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `execute with no args returns Failure with descriptive message`() = runTest {
        val result = skill.execute(emptyMap())

        assertTrue(result is SkillResult.Failure)
        assertTrue(
            (result as SkillResult.Failure).error.contains("At least one of")
        )
    }

    @Test
    fun `deep_link takes precedence over package_name`() = runTest {
        val uri = "https://open.spotify.com/track/123"
        val intentSlot = slot<Intent>()
        justRun { context.startActivity(capture(intentSlot)) }

        val result = skill.execute(
            mapOf(
                "deep_link" to uri,
                "package_name" to "com.spotify.music",
            )
        )

        assertTrue(result is SkillResult.Success)
        // Must have used ACTION_VIEW, not getLaunchIntentForPackage
        assertEquals(Intent.ACTION_VIEW, intentSlot.captured.action)
        verify(exactly = 0) { packageManager.getLaunchIntentForPackage(any()) }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private inline fun <reified T : Throwable> assertCauseIsInstanceOf(failure: SkillResult.Failure) {
        assertTrue(
            "Expected cause to be ${T::class.simpleName} but was ${failure.cause?.javaClass?.simpleName}",
            failure.cause is T,
        )
    }
}
