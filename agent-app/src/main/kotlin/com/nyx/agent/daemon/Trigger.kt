package com.nyx.agent.daemon

/**
 * Represents an event that causes the agent loop to act.
 */
sealed class Trigger {
    /** A new notification arrived. */
    data class NotificationReceived(val packageName: String, val title: String, val text: String) : Trigger()

    /** A scheduled time-based trigger. */
    data class TimeBased(val tag: String) : Trigger()

    /** An explicit control command from the user or daemon. */
    data class ControlCommand(val command: String, val args: Map<String, Any> = emptyMap()) : Trigger()
}
