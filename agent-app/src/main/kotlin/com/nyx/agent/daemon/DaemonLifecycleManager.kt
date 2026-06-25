package com.nyx.agent.daemon

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Controls [NyxAgentDaemon] lifecycle: start, stop, restart, and battery-optimization
 * exemption request.
 *
 * All methods are safe to call from any thread. The [start] and [stop] methods
 * delegate to the Android service machinery, which handles thread safety
 * internally.
 *
 * Usage:
 * ```kotlin
 * // In Application.onCreate or a boot receiver:
 * DaemonLifecycleManager.requestBatteryOptimizationExemption(context)
 * DaemonLifecycleManager.start(context)
 * ```
 */
object DaemonLifecycleManager {

    /**
     * Starts [NyxAgentDaemon] as a foreground service.
     *
     * On Android 8.0+ (API 26+) this calls [Context.startForegroundService] so the
     * system allows the service to call [android.app.Service.startForeground] within
     * the required 5-second window.
     *
     * Safe to call when the daemon is already running — the system will deliver an
     * additional [android.app.Service.onStartCommand] call, which the daemon ignores.
     *
     * @param context Application or activity context used to send the start intent.
     */
    fun start(context: Context) {
        val intent = Intent(context, NyxAgentDaemon::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Stops [NyxAgentDaemon].
     *
     * After this call, [NyxAgentDaemon.onDestroy] is invoked by the system and the
     * daemon's coroutine scope is cancelled. The daemon will NOT restart automatically
     * unless [start] is called again or the device reboots (via [BootReceiver]).
     *
     * @param context Application or activity context used to send the stop intent.
     */
    fun stop(context: Context) {
        val intent = Intent(context, NyxAgentDaemon::class.java)
        context.stopService(intent)
    }

    /**
     * Stops the daemon if it is running, then starts it again.
     *
     * Useful for applying configuration changes that require a full service restart.
     *
     * @param context Application or activity context.
     */
    fun restart(context: Context) {
        stop(context)
        start(context)
    }

    /**
     * Requests that the user exempts this application from Android Doze-mode battery
     * optimizations.
     *
     * Launches [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] so the user is
     * presented with a system dialog. This is a no-op if the app is already exempted.
     *
     * **Requires** the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission in the manifest.
     *
     * Note: Google Play's policy restricts the use of this permission for apps that are
     * not system utilities or VPN clients. Verify compliance before publishing.
     *
     * @param context Application or activity context. Must be an [android.app.Activity]
     *   context if the intent needs to be started with a back-stack.
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * Returns `true` if [NyxAgentDaemon] is currently running as a foreground service.
     *
     * Checks the [ActivityManager] service list for a running instance of
     * [NyxAgentDaemon]. This is an O(n) scan over all running services and should not
     * be called in a tight loop.
     *
     * @param context Application or activity context.
     * @return `true` if the daemon service is in the running services list.
     */
    @Suppress("DEPRECATION") // getRunningServices is deprecated but remains the correct
    // approach for checking your own service's status.
    fun isRunning(context: Context): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val daemonClassName = NyxAgentDaemon::class.java.name
        return activityManager
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == daemonClassName }
    }
}
