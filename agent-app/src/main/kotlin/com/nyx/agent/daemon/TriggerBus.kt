package com.nyx.agent.daemon

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide event bus for [Trigger] events.
 *
 * Notification listeners, WorkManager workers, and IPC handlers all emit here.
 * [AgentLoop] collects from [events].
 *
 * The bus uses a [MutableSharedFlow] with no replay so late subscribers only
 * see events emitted after they subscribe. An extra buffer of 64 events absorbs
 * bursts without dropping on the emitter side.
 */
object TriggerBus {
    private val _events = MutableSharedFlow<Trigger>(replay = 0, extraBufferCapacity = 64)

    /** Read-only view of the event stream. Collect from [AgentLoop]. */
    val events: SharedFlow<Trigger> = _events.asSharedFlow()

    /**
     * Suspends until the trigger can be delivered into the buffer.
     * Prefer this from coroutine context (e.g., notification listeners).
     */
    suspend fun emit(trigger: Trigger) {
        _events.emit(trigger)
    }

    /**
     * Non-suspending fire-and-forget emit. Returns false if the buffer is full.
     * Safe to call from non-coroutine contexts such as BroadcastReceivers.
     */
    fun tryEmit(trigger: Trigger): Boolean = _events.tryEmit(trigger)
}
