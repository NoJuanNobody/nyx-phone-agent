package com.nyx.agent.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Broadcast receiver that restarts [NyxAgentDaemon] after device reboot.
 *
 * Triggered by [android.intent.action.BOOT_COMPLETED] and
 * [android.intent.action.MY_PACKAGE_REPLACED]. Requires the
 * `RECEIVE_BOOT_COMPLETED` permission in the manifest.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DaemonLifecycleManager.start(context)
            }
        }
    }
}
