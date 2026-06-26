package com.nyx.agent.skill.impl

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Production [UiInteractionBridge] backed by [AccessibilityService.dispatchGesture].
 * The service must be connected before use — check [isConnected] first.
 *
 * Register as an AccessibilityService in AndroidManifest.xml with:
 *   android:accessibilityFlags="flagRequestTouchExplorationMode"
 *   android:canPerformGestures="true"
 */
class AccessibilityUiBridge(private val service: AccessibilityService) : UiInteractionBridge {
    val isConnected: Boolean get() = try { service.rootInActiveWindow != null } catch (e: Exception) { false }

    private fun buildPath(x: Float, y: Float, endX: Float? = null, endY: Float? = null) = Path().apply {
        moveTo(x, y)
        if (endX != null && endY != null) lineTo(endX, endY)
    }

    private fun dispatchGesture(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var result = false
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { result = true }
            override fun onCancelled(gestureDescription: GestureDescription) { result = false }
        }, null)
        return result
    }

    override fun tap(x: Float, y: Float) = dispatchGesture(buildPath(x, y), 50L)
    override fun scroll(x: Float, y: Float, endX: Float, endY: Float, durationMs: Long) =
        dispatchGesture(buildPath(x, y, endX, endY), durationMs)
    override fun swipe(x: Float, y: Float, endX: Float, endY: Float, durationMs: Long) =
        dispatchGesture(buildPath(x, y, endX, endY), durationMs)
}
