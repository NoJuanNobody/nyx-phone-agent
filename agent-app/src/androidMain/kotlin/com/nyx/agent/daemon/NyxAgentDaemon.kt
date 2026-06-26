package com.nyx.agent.daemon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nyx.agent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Persistent foreground service that hosts the Nyx agent runtime.
 *
 * Survives app backgrounding, device sleep, and process restarts.
 * Uses [START_STICKY] so Android restarts the service automatically if it
 * is killed by the OS under memory pressure.
 *
 * The service is started via [DaemonLifecycleManager.start] and should never
 * be started directly with a raw [Intent].
 *
 * Lifecycle:
 * - [onCreate]: creates the notification channel and moves the service to the
 *   foreground with a persistent notification. A [CoroutineScope] is created
 *   here and is available for the lifetime of the service.
 * - [onStartCommand]: called each time [DaemonLifecycleManager.start] is invoked.
 *   Returns [START_STICKY] so the system recreates the service if it is killed.
 * - [onBind]: returns `null` — this is a started service, not a bound service.
 * - [onDestroy]: cancels the coroutine scope, releasing all running coroutines.
 */
class NyxAgentDaemon : Service() {

    companion object {
        /** Notification channel ID for the daemon persistent notification. */
        const val CHANNEL_ID = "nyx_daemon_channel"

        /** Notification ID used to keep the service in the foreground. */
        private const val NOTIFICATION_ID = 1001
    }

    /**
     * Coroutine scope tied to the service lifetime.
     * All agent background work should be launched on this scope so that
     * cancellation is guaranteed when the service is destroyed.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    /**
     * Called once when the service is first created. Sets up the notification
     * channel (Android 8.0+ requirement) and moves the service into the
     * foreground immediately to avoid ANR / background-start restrictions.
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Called each time the service is started via [Context.startForegroundService]
     * or [Context.startService].
     *
     * @return [START_STICKY] so the system automatically recreates the service
     * with a `null` intent if it is killed while in the foreground.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service is already in the foreground from onCreate; nothing extra needed here.
        // Subclasses or future work units can be dispatched via serviceScope here.
        return START_STICKY
    }

    /**
     * NyxAgentDaemon is a started (non-bound) service.
     *
     * @return `null` — clients must communicate with the daemon via [Intent]s or
     * a separate IPC mechanism, not binding.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called when the service is about to be destroyed. Cancels [serviceScope]
     * to release all coroutines launched within the daemon.
     */
    override fun onDestroy() {
        serviceScope.cancel("NyxAgentDaemon destroyed")
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    /**
     * Creates the [NotificationChannel] required for foreground services on
     * Android 8.0 (API 26) and above. Safe to call multiple times — the system
     * is idempotent.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.daemon_notification_title),
            NotificationManager.IMPORTANCE_LOW // Low importance: no sound/vibration
        ).apply {
            description = getString(R.string.daemon_notification_text)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Builds the persistent notification displayed while the daemon is running.
     */
    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.daemon_notification_title))
        .setContentText(getString(R.string.daemon_notification_text))
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}
