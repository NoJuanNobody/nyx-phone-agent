package com.nyx.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Nyx's Accessibility Service for UI automation on Path B.
 *
 * Capabilities:
 * - Read on-screen content (text, element roles)
 * - Find elements by text or content description
 * - Dispatch click/scroll gestures (used by [com.nyx.agent.skill.impl.UiInteractionSkill])
 *
 * Must be declared in AndroidManifest.xml with:
 *   android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
 *   and the accessibility service config XML.
 */
class NyxAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: NyxAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.also { info ->
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                         AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            info.notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* handled by UiInteractionSkill */ }
    override fun onInterrupt() { instance = null }
    override fun onDestroy() { instance = null; super.onDestroy() }

    /** Find an on-screen node matching [text] (exact or contains). */
    fun findByText(text: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()

    /** Dump all visible node texts for screenshot-free observation. */
    fun dumpScreenText(): List<String> {
        val result = mutableListOf<String>()
        fun traverse(node: AccessibilityNodeInfo?) {
            node ?: return
            node.text?.let { result.add(it.toString()) }
            for (i in 0 until node.childCount) traverse(node.getChild(i))
        }
        traverse(rootInActiveWindow)
        return result
    }
}
