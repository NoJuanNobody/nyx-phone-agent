package com.nyx.agent.bridge

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder

/**
 * Binds to the Shizuku service to obtain ADB-level shell access on non-rooted devices.
 *
 * Shizuku must be installed and activated by the user before this bridge works.
 * Call [checkAvailable] before [bind] — returns [ElevationLevel.NONE] if Shizuku
 * is not installed or permission is not granted.
 *
 * Requires: implementation("dev.rikka.shizuku:api:13.1.5") and provider in manifest.
 * Until that dep is wired, methods stub their behavior.
 */
class ShizukuBridge {
    enum class BindState { UNBOUND, BINDING, BOUND, ERROR }

    var state: BindState = BindState.UNBOUND
        private set

    /**
     * Returns true if Shizuku is installed, running, and permission is granted.
     */
    fun checkAvailable(): Boolean {
        // In production: Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        return false  // stub: Shizuku dep not wired
    }

    /**
     * Request Shizuku permission from the user if not already granted.
     */
    fun requestPermission() {
        // In production: Shizuku.requestPermission(REQUEST_CODE)
    }

    /**
     * Execute a shell command via Shizuku's elevated IPC channel.
     * @return stdout output as string, or null on failure.
     */
    fun exec(command: String): String? {
        if (!checkAvailable()) return null
        // In production: use Shizuku's IUserService binder to run shell command
        return null  // stub
    }

    companion object {
        const val REQUEST_CODE = 42
    }
}
