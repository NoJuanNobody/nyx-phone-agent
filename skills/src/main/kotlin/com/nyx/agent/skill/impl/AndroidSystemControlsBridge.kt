package com.nyx.agent.skill.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings

/**
 * Production bridge that delegates to real Android system APIs.
 *
 * Required permissions declared in the app manifest:
 *   - android.permission.CHANGE_WIFI_STATE
 *   - android.permission.BLUETOOTH_CONNECT
 *   - android.permission.MODIFY_AUDIO_SETTINGS
 *   - android.permission.WRITE_SETTINGS
 *
 * Note: On Android 10+ the WifiManager.setWifiEnabled() API is no longer
 * available to third-party apps. A Settings panel intent is the correct
 * user-facing alternative, but the interface is kept for agent-driven
 * automation on rooted / privileged builds where the permission is granted.
 */
class AndroidSystemControlsBridge(private val context: Context) : SystemControlsBridge {

    // ── Wi-Fi ─────────────────────────────────────────────────────────────────

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    override fun getWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    @Suppress("DEPRECATION")
    override fun setWifiEnabled(enabled: Boolean) {
        wifiManager.isWifiEnabled = enabled
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────────

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    override fun getBluetoothEnabled(): Boolean =
        bluetoothAdapter?.isEnabled ?: false

    @Suppress("DEPRECATION", "MissingPermission")
    override fun setBluetoothEnabled(enabled: Boolean) {
        val adapter = bluetoothAdapter
            ?: throw IllegalStateException("Device does not have a Bluetooth adapter")
        if (enabled) {
            adapter.enable()
        } else {
            adapter.disable()
        }
    }

    // ── Volume ────────────────────────────────────────────────────────────────

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun streamTypeInt(streamType: String): Int = when (streamType) {
        "ringer" -> AudioManager.STREAM_RING
        else -> AudioManager.STREAM_MUSIC  // default: "media"
    }

    override fun getVolume(streamType: String): Int {
        val stream = streamTypeInt(streamType)
        val max = audioManager.getStreamMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream)
        // Return as 0–100 normalised value
        return if (max > 0) (current * 100) / max else 0
    }

    override fun setVolume(streamType: String, level: Int) {
        val stream = streamTypeInt(streamType)
        val max = audioManager.getStreamMaxVolume(stream)
        val actual = (level.coerceIn(0, 100) * max) / 100
        audioManager.setStreamVolume(stream, actual, 0)
    }

    // ── Brightness ────────────────────────────────────────────────────────────

    override fun getBrightness(): Int =
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128  // fallback mid-level
        )

    override fun setBrightness(level: Int) {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            level.coerceIn(0, 255)
        )
    }
}
