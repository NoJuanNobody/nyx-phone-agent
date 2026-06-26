package com.nyx.agent.daemon

import android.content.Context
import android.util.Log
import com.nyx.agent.skill.SkillRegistry
import com.nyx.agent.skill.SkillResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

private const val TAG = "AgentLoop"

/**
 * The core agent execution loop. Runs inside [NyxAgentDaemon] as a coroutine.
 *
 * Responsibilities:
 * - Subscribe to [TriggerBus] for incoming events
 * - Dispatch triggered skill calls via [SkillRegistry]
 * - Persist loop state (running/stopped) across restarts via [LoopStateStore]
 * - Honour the emergency kill switch via [emergencyStop]
 *
 * Typical lifecycle managed by [NyxAgentDaemon]:
 * ```
 * val loop = AgentLoop(context, registry, lifecycleScope)
 * if (loop.wasRunningBeforeRestart()) loop.start()
 * ```
 */
class AgentLoop(
    private val context: Context,
    private val registry: SkillRegistry,
    private val scope: CoroutineScope,
) {
    private var loopJob: Job? = null

    /**
     * Start the agent loop. No-op if already running.
     * Persists [LoopStateStore.KEY] = true so the loop auto-resumes after restart.
     */
    fun start() {
        if (loopJob?.isActive == true) return
        LoopStateStore.setRunning(context, true)
        loopJob = scope.launch {
            runLoop()
        }
        Log.i(TAG, "AgentLoop started")
    }

    /**
     * Stop the loop gracefully by cancelling the loop coroutine and awaiting its
     * completion. Persists [LoopStateStore.KEY] = false so restarts do not
     * auto-resume.
     */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        LoopStateStore.setRunning(context, false)
        Log.i(TAG, "AgentLoop stopped")
    }

    /**
     * Emergency kill — immediately cancels the entire [scope], bringing down the
     * loop and any in-flight skill coroutines without waiting for graceful
     * shutdown. Also persists stopped state.
     *
     * After this call the [scope] is no longer usable; the host service should
     * restart if it needs the loop again.
     */
    fun emergencyStop() {
        LoopStateStore.setRunning(context, false)
        scope.cancel("emergency stop")
        loopJob = null
        Log.w(TAG, "AgentLoop emergency stop triggered")
    }

    /** Returns true if the loop coroutine is currently active. */
    val isRunning: Boolean
        get() = loopJob?.isActive == true

    /**
     * Returns the persisted state from the last session so the daemon can decide
     * whether to auto-start the loop on restart.
     */
    fun wasRunningBeforeRestart(): Boolean = LoopStateStore.wasRunning(context)

    // -------------------------------------------------------------------------
    // Private loop body
    // -------------------------------------------------------------------------

    /**
     * Suspends while collecting [Trigger] events from [TriggerBus] and
     * dispatching matching skills via [SkillRegistry].
     *
     * Errors from individual skill dispatches are caught and logged so a single
     * bad trigger cannot crash the loop.
     */
    private suspend fun runLoop() {
        TriggerBus.events
            .catch { e -> Log.e(TAG, "TriggerBus error — restarting collection", e) }
            .collect { trigger ->
                handleTrigger(trigger)
            }
    }

    private suspend fun handleTrigger(trigger: Trigger) {
        Log.d(TAG, "Handling trigger: $trigger")
        try {
            when (trigger) {
                is Trigger.NotificationReceived -> {
                    val skillName = registry.skillForNotification(trigger.packageName)
                    if (skillName != null) {
                        val result = registry.execute(skillName, mapOf(
                            "packageName" to trigger.packageName,
                            "title" to trigger.title,
                            "text" to trigger.text,
                        ))
                        logResult(skillName, result)
                    }
                }

                is Trigger.TimeBased -> {
                    val result = registry.execute(trigger.tag, emptyMap())
                    logResult(trigger.tag, result)
                }

                is Trigger.ControlCommand -> {
                    when (trigger.command) {
                        "stop" -> stop()
                        "emergency_stop" -> emergencyStop()
                        else -> {
                            val result = registry.execute(trigger.command, trigger.args)
                            logResult(trigger.command, result)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e // always re-throw so the loop exits cleanly
        } catch (e: Exception) {
            Log.e(TAG, "Error handling trigger $trigger", e)
        }
    }

    private fun logResult(skill: String, result: SkillResult) {
        if (result.success) {
            Log.i(TAG, "Skill '$skill' succeeded: ${result.output}")
        } else {
            Log.w(TAG, "Skill '$skill' failed: ${result.error}")
        }
    }
}
