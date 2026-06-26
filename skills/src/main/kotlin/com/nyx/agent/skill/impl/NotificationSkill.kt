package com.nyx.agent.skill.impl

import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

/**
 * Notification data returned by [NotificationBridge.listNotifications].
 * Content is NOT logged — callers must keep this out of any log sinks.
 */
data class NotificationInfo(
    val id: Int,
    val packageName: String,
    val title: String,
    val text: String,
    val actions: List<String>,  // action labels
)

/**
 * Abstraction over the NotificationListenerService so [NotificationManagementSkill]
 * can be unit-tested without Robolectric.
 */
interface NotificationBridge {
    fun listNotifications(): List<NotificationInfo>
    fun dismiss(notificationId: Int): Boolean
    fun triggerAction(notificationId: Int, actionLabel: String): Boolean
}

/**
 * Skill for reading and acting on Android notifications.
 *
 * Requires the app to be granted `BIND_NOTIFICATION_LISTENER_SERVICE` (user must enable
 * in Settings → Notification Access). Acting on notifications (dismiss/action) requires
 * user confirmation.
 *
 * Args:
 * - `action`: "list" | "dismiss" | "trigger_action"
 * - `notification_id` (Int): required for dismiss and trigger_action
 * - `action_label` (String): required for trigger_action (e.g. "Reply", "Mark as read")
 *
 * IMPORTANT: notification content (title, text) must never be written to logs.
 */
class NotificationManagementSkill(private val bridge: NotificationBridge) : Skill {
    override val name = "manage_notifications"
    override val description = "List, dismiss, or act on Android notifications"
    override val requiresConfirmation = true  // for dismiss and trigger_action

    override suspend fun execute(args: Map<String, Any>): SkillResult {
        return when (val action = args["action"] as? String) {
            "list" -> {
                val notifications = bridge.listNotifications()
                SkillResult.Success(mapOf(
                    "count" to notifications.size,
                    "notifications" to notifications.map { n ->
                        mapOf("id" to n.id, "app" to n.packageName,
                              "title" to n.title, "actions" to n.actions)
                        // NOTE: `text` is intentionally omitted from the returned map to reduce log exposure
                    }
                ))
            }
            "dismiss" -> {
                val id = (args["notification_id"] as? Number)?.toInt()
                    ?: return SkillResult.Failure("notification_id is required for dismiss")
                if (bridge.dismiss(id)) SkillResult.Success(mapOf("dismissed_id" to id))
                else SkillResult.Failure("Failed to dismiss notification $id")
            }
            "trigger_action" -> {
                val id = (args["notification_id"] as? Number)?.toInt()
                    ?: return SkillResult.Failure("notification_id is required for trigger_action")
                val label = args["action_label"] as? String
                    ?: return SkillResult.Failure("action_label is required for trigger_action")
                if (bridge.triggerAction(id, label)) SkillResult.Success(mapOf("triggered" to label))
                else SkillResult.Failure("Action '$label' not found on notification $id")
            }
            else -> SkillResult.Failure("Unknown action '$action'. Use list, dismiss, or trigger_action")
        }
    }
}
