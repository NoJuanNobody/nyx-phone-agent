package com.nyx.agent.skill.impl

import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

/**
 * Abstraction over Android system APIs for testability without real hardware.
 */
interface SystemControlsBridge {
    fun getWifiEnabled(): Boolean
    fun setWifiEnabled(enabled: Boolean)
    fun getBluetoothEnabled(): Boolean
    fun setBluetoothEnabled(enabled: Boolean)
    fun getVolume(streamType: String): Int   // streamType: "media" | "ringer"
    fun setVolume(streamType: String, level: Int)  // 0..maxVolume
    fun getBrightness(): Int   // 0..255
    fun setBrightness(level: Int)  // 0..255
}

/**
 * Skill for toggling Android system settings.
 *
 * Args:
 * - `control`: "wifi" | "bluetooth" | "volume" | "brightness"
 * - `action`: "get" | "set" | "toggle"
 * - `enabled` (Boolean): for set on wifi/bluetooth
 * - `stream` (String): "media" | "ringer" — for volume
 * - `level` (Int): 0-100 normalized — for volume/brightness (skill scales to actual range)
 *
 * Each successful set reports {"before": ..., "after": ...} state.
 */
class SystemControlsSkill(private val bridge: SystemControlsBridge) : Skill {
    override val name = "system_controls"
    override val description = "Toggle Wi-Fi, Bluetooth, set volume or brightness"
    override val requiredPermissions = listOf(
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.WRITE_SETTINGS",
    )

    override suspend fun execute(args: Map<String, Any>): SkillResult {
        val control = args["control"] as? String
            ?: return SkillResult.Failure("'control' is required: wifi, bluetooth, volume, brightness")
        val action = args["action"] as? String ?: "get"

        return when (control) {
            "wifi" -> handleWifi(action, args)
            "bluetooth" -> handleBluetooth(action, args)
            "volume" -> handleVolume(action, args)
            "brightness" -> handleBrightness(action, args)
            else -> SkillResult.Failure("Unknown control '$control'. Use: wifi, bluetooth, volume, brightness")
        }
    }

    private fun handleWifi(action: String, args: Map<String, Any>): SkillResult {
        val before = bridge.getWifiEnabled()
        return when (action) {
            "get" -> SkillResult.Success(mapOf("wifi_enabled" to before))
            "set" -> {
                val enabled = args["enabled"] as? Boolean
                    ?: return SkillResult.Failure("'enabled' (Boolean) required for set")
                bridge.setWifiEnabled(enabled)
                SkillResult.Success(mapOf("before" to before, "after" to enabled))
            }
            "toggle" -> {
                bridge.setWifiEnabled(!before)
                SkillResult.Success(mapOf("before" to before, "after" to !before))
            }
            else -> SkillResult.Failure("Unknown action '$action'. Use get, set, or toggle")
        }
    }

    private fun handleBluetooth(action: String, args: Map<String, Any>): SkillResult {
        val before = bridge.getBluetoothEnabled()
        return when (action) {
            "get" -> SkillResult.Success(mapOf("bluetooth_enabled" to before))
            "set" -> {
                val enabled = args["enabled"] as? Boolean
                    ?: return SkillResult.Failure("'enabled' (Boolean) required for set")
                bridge.setBluetoothEnabled(enabled)
                SkillResult.Success(mapOf("before" to before, "after" to enabled))
            }
            "toggle" -> {
                bridge.setBluetoothEnabled(!before)
                SkillResult.Success(mapOf("before" to before, "after" to !before))
            }
            else -> SkillResult.Failure("Unknown action '$action'. Use get, set, or toggle")
        }
    }

    private fun handleVolume(action: String, args: Map<String, Any>): SkillResult {
        val stream = args["stream"] as? String ?: "media"
        val before = bridge.getVolume(stream)
        return when (action) {
            "get" -> SkillResult.Success(mapOf("stream" to stream, "volume" to before))
            "set" -> {
                val level = (args["level"] as? Number)?.toInt()
                    ?: return SkillResult.Failure("'level' (0-100) required for set")
                bridge.setVolume(stream, level.coerceIn(0, 100))
                SkillResult.Success(mapOf("stream" to stream, "before" to before, "after" to level))
            }
            "mute" -> {
                bridge.setVolume(stream, 0)
                SkillResult.Success(mapOf("stream" to stream, "before" to before, "after" to 0))
            }
            else -> SkillResult.Failure("Unknown action '$action'. Use get, set, or mute")
        }
    }

    private fun handleBrightness(action: String, args: Map<String, Any>): SkillResult {
        val before = bridge.getBrightness()
        return when (action) {
            "get" -> SkillResult.Success(mapOf("brightness" to before))
            "set" -> {
                val level = (args["level"] as? Number)?.toInt()
                    ?: return SkillResult.Failure("'level' (0-255) required for set")
                bridge.setBrightness(level.coerceIn(0, 255))
                SkillResult.Success(mapOf("before" to before, "after" to level))
            }
            else -> SkillResult.Failure("Unknown action '$action'. Use get or set")
        }
    }
}
