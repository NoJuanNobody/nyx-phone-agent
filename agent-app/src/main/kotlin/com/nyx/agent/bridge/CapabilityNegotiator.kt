package com.nyx.agent.bridge

/**
 * Detects the highest available elevation level on the current device and
 * selects the appropriate bridge for privileged operations.
 *
 * Priority order: Root (Magisk) > Shizuku > Accessibility-only (unprivileged)
 */
class CapabilityNegotiator(
    private val shizuku: ShizukuBridge,
    private val root: RootBridge,
) {
    enum class ElevationLevel { ROOT, SHIZUKU, ACCESSIBILITY_ONLY }

    /** Detect and return the highest available elevation level. */
    fun detect(): ElevationLevel = when {
        root.isRooted() -> ElevationLevel.ROOT
        shizuku.checkAvailable() -> ElevationLevel.SHIZUKU
        else -> ElevationLevel.ACCESSIBILITY_ONLY
    }

    /**
     * Execute a privileged shell command using the best available bridge.
     * Returns null if even Shizuku/root are unavailable for this command.
     */
    fun exec(command: String): String? = when (detect()) {
        ElevationLevel.ROOT -> root.exec(command)
        ElevationLevel.SHIZUKU -> shizuku.exec(command)
        ElevationLevel.ACCESSIBILITY_ONLY -> null  // command requires elevation
    }
}
