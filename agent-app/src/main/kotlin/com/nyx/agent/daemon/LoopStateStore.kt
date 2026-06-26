package com.nyx.agent.daemon

import android.content.Context

/**
 * Persists [AgentLoop] running/stopped state to SharedPreferences so the daemon
 * can resume the loop after process restart.
 *
 * Writes are applied asynchronously via [apply] to avoid blocking the calling thread.
 */
object LoopStateStore {
    private const val PREFS = "agent_loop"
    private const val KEY = "was_running"

    /**
     * Persist whether the loop was running. Call on every [AgentLoop.start] and
     * [AgentLoop.stop] transition so restarts can restore the correct state.
     */
    fun setRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, running)
            .apply()
    }

    /**
     * Returns true if the loop was running the last time [setRunning] was called
     * with `true`. Defaults to false on first run.
     */
    fun wasRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
}
