package com.nyx.agent.daemon

import android.content.Context
import android.content.SharedPreferences
import com.nyx.agent.skill.SkillRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentLoopTest {

    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var registry: SkillRegistry
    private lateinit var testScope: TestScope
    private lateinit var loop: AgentLoop

    @BeforeEach
    fun setUp() {
        editor = mockk(relaxed = true)
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.apply() } returns Unit

        sharedPrefs = mockk {
            every { edit() } returns editor
            every { getBoolean(any(), any()) } returns false
        }

        context = mockk {
            every { getSharedPreferences("agent_loop", Context.MODE_PRIVATE) } returns sharedPrefs
        }

        registry = SkillRegistry()

        testScope = TestScope()
        loop = AgentLoop(context, registry, testScope)
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    // -------------------------------------------------------------------------
    // start / stop / emergencyStop
    // -------------------------------------------------------------------------

    @Test
    fun `start sets isRunning to true`() {
        assertFalse(loop.isRunning, "Should not be running before start")
        loop.start()
        assertTrue(loop.isRunning, "Should be running after start")
    }

    @Test
    fun `start persists running state`() {
        loop.start()
        verify { editor.putBoolean(AgentLoop.KEY_WAS_RUNNING, true) }
        verify { editor.apply() }
    }

    @Test
    fun `start is idempotent when already running`() {
        loop.start()
        val firstJob = loop.isRunning
        loop.start() // second call should no-op
        assertTrue(firstJob)
        assertTrue(loop.isRunning)
    }

    @Test
    fun `stop sets isRunning to false`() {
        loop.start()
        assertTrue(loop.isRunning)
        loop.stop()
        assertFalse(loop.isRunning, "Should not be running after stop")
    }

    @Test
    fun `stop persists stopped state`() {
        loop.start()
        loop.stop()
        verify { editor.putBoolean(AgentLoop.KEY_WAS_RUNNING, false) }
    }

    @Test
    fun `emergencyStop cancels immediately`() {
        loop.start()
        assertTrue(loop.isRunning)
        loop.emergencyStop()
        assertFalse(loop.isRunning, "Should not be running after emergencyStop")
        assertTrue(testScope.coroutineContext.job.isCancelled, "Scope should be cancelled")
    }

    @Test
    fun `emergencyStop persists stopped state`() {
        loop.start()
        loop.emergencyStop()
        verify { editor.putBoolean(AgentLoop.KEY_WAS_RUNNING, false) }
    }

    // -------------------------------------------------------------------------
    // TriggerBus delivery
    // -------------------------------------------------------------------------

    @Test
    fun `TriggerBus tryEmit delivers to collectors`(): Unit = runBlocking {
        val collected = mutableListOf<Trigger>()
        val job = launch {
            TriggerBus.events.collect { collected.add(it) }
        }
        // TriggerBus is a replay-less SharedFlow, so wait for the collector to
        // subscribe before emitting, otherwise the event is dropped.
        delay(50)
        val trigger = Trigger.NotificationReceived("com.example", "Hello", "World")
        val emitted = TriggerBus.tryEmit(trigger)
        assertTrue(emitted, "tryEmit should succeed with buffer space")

        // Give the collector a moment to receive the event
        delay(50)
        job.cancel()

        assertTrue(collected.contains(trigger), "Collector should have received the trigger")
    }

    @Test
    fun `TriggerBus emit delivers to multiple collectors`(): Unit = runBlocking {
        val collected1 = mutableListOf<Trigger>()
        val collected2 = mutableListOf<Trigger>()

        val job1 = launch { TriggerBus.events.collect { collected1.add(it) } }
        val job2 = launch { TriggerBus.events.collect { collected2.add(it) } }

        // Wait for both collectors to subscribe before emitting (replay-less flow).
        delay(50)
        val trigger = Trigger.TimeBased("scheduled-check")
        TriggerBus.tryEmit(trigger)
        delay(50)

        job1.cancel()
        job2.cancel()

        assertTrue(collected1.contains(trigger))
        assertTrue(collected2.contains(trigger))
    }

    // -------------------------------------------------------------------------
    // LoopStateStore persistence
    // -------------------------------------------------------------------------

    @Test
    fun `LoopStateStore setRunning true and read back`() {
        every { sharedPrefs.getBoolean("was_running", false) } returns true

        LoopStateStore.setRunning(context, true)
        val result = LoopStateStore.wasRunning(context)

        verify { editor.putBoolean("was_running", true) }
        assertTrue(result, "wasRunning should return true after setRunning(true)")
    }

    @Test
    fun `LoopStateStore setRunning false and read back`() {
        every { sharedPrefs.getBoolean("was_running", false) } returns false

        LoopStateStore.setRunning(context, false)
        val result = LoopStateStore.wasRunning(context)

        verify { editor.putBoolean("was_running", false) }
        assertFalse(result, "wasRunning should return false after setRunning(false)")
    }

    @Test
    fun `LoopStateStore defaults to false on first run`() {
        every { sharedPrefs.getBoolean("was_running", false) } returns false

        val result = LoopStateStore.wasRunning(context)
        assertFalse(result, "Default state should be false")
    }

    @Test
    fun `wasRunningBeforeRestart delegates to LoopStateStore`() {
        every { sharedPrefs.getBoolean(AgentLoop.KEY_WAS_RUNNING, false) } returns true

        assertTrue(loop.wasRunningBeforeRestart())
    }
}
