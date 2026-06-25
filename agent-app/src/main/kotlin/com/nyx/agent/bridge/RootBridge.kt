package com.nyx.agent.bridge

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Optional Magisk-rooted execution path for actions that Shizuku cannot cover.
 * Falls back to [ShizukuBridge] when root is unavailable.
 *
 * Root access is detected by attempting `su -c id`. If it returns uid=0, root is available.
 */
class RootBridge {
    /**
     * Returns true if the device is rooted and `su` is accessible.
     */
    fun isRooted(): Boolean = try {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = proc.inputStream.bufferedReader().readLine() ?: ""
        proc.waitFor() == 0 && output.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    /**
     * Execute a shell command as root via `su -c`.
     * @return stdout as string, or null if root is unavailable or command fails.
     */
    fun exec(command: String): String? {
        if (!isRooted()) return null
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            if (exitCode == 0) output.trim() else null
        } catch (e: Exception) {
            null
        }
    }
}
