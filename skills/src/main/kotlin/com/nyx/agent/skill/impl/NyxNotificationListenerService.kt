package com.nyx.agent.skill.impl

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Android NotificationListenerService implementation that backs [NotificationBridge].
 * Must be declared in AndroidManifest.xml with the BIND_NOTIFICATION_LISTENER_SERVICE permission.
 */
class NyxNotificationListenerService : NotificationListenerService(), NotificationBridge {
    override fun listNotifications(): List<NotificationInfo> =
        activeNotifications.orEmpty().map { sbn ->
            val extras = sbn.notification.extras
            NotificationInfo(
                id = sbn.id,
                packageName = sbn.packageName,
                title = extras.getString("android.title") ?: "",
                text = extras.getString("android.text") ?: "",
                actions = sbn.notification.actions?.map { it.title.toString() } ?: emptyList(),
            )
        }

    override fun dismiss(notificationId: Int): Boolean {
        cancelNotification(activeNotifications.orEmpty()
            .firstOrNull { it.id == notificationId }?.key ?: return false)
        return true
    }

    override fun triggerAction(notificationId: Int, actionLabel: String): Boolean {
        val sbn = activeNotifications.orEmpty().firstOrNull { it.id == notificationId } ?: return false
        val action = sbn.notification.actions?.firstOrNull {
            it.title.toString().equals(actionLabel, ignoreCase = true)
        } ?: return false
        action.actionIntent.send()
        return true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
