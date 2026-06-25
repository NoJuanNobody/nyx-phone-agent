package com.nyx.agent.skill.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

/**
 * Skill that launches an installed Android application.
 *
 * Accepted [args] keys:
 * - `package_name` (String) — exact package name (preferred); e.g. `"com.google.android.gm"`
 * - `app_name` (String) — fuzzy matched (case-insensitive substring) against installed app labels;
 *   e.g. `"Gmail"` or `"chrome"`
 * - `deep_link` (String, optional) — URI to open via [Intent.ACTION_VIEW] instead of the
 *   launcher intent; e.g. `"spotify:track:4uLU6hMCjMI75M1A2tKUQC"`
 *
 * Resolution order when multiple args are provided:
 * 1. `deep_link` — fired directly as [Intent.ACTION_VIEW] (ignores `package_name` / `app_name`)
 * 2. `package_name` — resolved via [PackageManager.getLaunchIntentForPackage]
 * 3. `app_name` — fuzzy matched against [PackageManager.getInstalledApplications] labels
 *
 * Returns [SkillResult.Success] with key `launched_package` on success, or
 * [SkillResult.Failure] with a human-readable reason on failure.
 *
 * Common packages for reference:
 * - Gmail: `com.google.android.gm`
 * - Spotify: `com.spotify.music`
 * - Chrome: `com.android.chrome`
 */
class LaunchAppSkill(private val context: Context) : Skill {

    override val name = "launch_app"
    override val description =
        "Launch an installed app by package name, fuzzy app name, or deep link URI"

    override suspend fun execute(args: Map<String, Any>): SkillResult {
        val packageName = args["package_name"] as? String
        val appName = args["app_name"] as? String
        val deepLink = args["deep_link"] as? String

        return when {
            deepLink != null -> launchDeepLink(deepLink)
            packageName != null -> launchByPackage(packageName)
            appName != null -> launchByAppName(appName)
            else -> SkillResult.Failure(
                "At least one of 'package_name', 'app_name', or 'deep_link' must be provided"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun launchDeepLink(uri: String): SkillResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            SkillResult.Success(mapOf("launched_deep_link" to uri))
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("No app found to handle deep link: $uri", e)
        } catch (e: Exception) {
            SkillResult.Failure("Failed to launch deep link '$uri': ${e.message}", e)
        }
    }

    private fun launchByPackage(packageName: String): SkillResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return SkillResult.Failure("No app installed with package name: $packageName")

        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SkillResult.Success(mapOf("launched_package" to packageName))
        } catch (e: ActivityNotFoundException) {
            SkillResult.Failure("App found but could not be launched: $packageName", e)
        } catch (e: Exception) {
            SkillResult.Failure("Failed to launch '$packageName': ${e.message}", e)
        }
    }

    private fun launchByAppName(appName: String): SkillResult {
        val resolvedPackage = fuzzyMatch(appName)
            ?: return SkillResult.Failure("No installed app matching name: '$appName'")

        return launchByPackage(resolvedPackage)
    }

    /**
     * Performs a case-insensitive substring match of [appName] against the labels of all
     * installed applications.
     *
     * @param appName The human-readable name to search for (e.g. `"Gmail"`, `"chrome"`).
     * @return The package name of the first matching app, or `null` if none found.
     */
    private fun fuzzyMatch(appName: String): String? {
        val pm = context.packageManager
        val query = appName.lowercase()

        @Suppress("DEPRECATION")
        val installedApps: List<ApplicationInfo> =
            pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return installedApps.firstOrNull { appInfo ->
            val label = pm.getApplicationLabel(appInfo).toString().lowercase()
            label.contains(query)
        }?.packageName
    }
}
