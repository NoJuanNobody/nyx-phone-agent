package com.nyx.agent.daemon

import android.content.Context
import android.content.SharedPreferences
import com.nyx.agent.skill.SkillRegistry
import com.nyx.agent.skill.SkillResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentLoopTest {

    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var registry: SkillRegistry
    private lateinit var testScope: TestScope
    private lateinit var loop: AgentLoop

    @Before
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

        registry = mockk(relaxed = true) {
            every { skillForNotification(any()) } returns null
            every { execute(any(), any()) } returns SkillResult(success = true, output = "ok")
        }

        testScope = TestScope()
        loop = AgentLoop(context, registry, testScope)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    // -------------------------------------------------------------------------
    // start / stop / emergencyStop
    // -------------------------------------------------------------------------

    @Test
    fun `start sets isRunning to true`() {
        assertFalse("Should not be running before start", loop.isRunning)
        loop.start()
        assertTrue("Should be running after start", loop.isRunning)
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
        assertFalse("Should not be running after stop", loop.isRunning)
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
        assertFalse("Should not be running after emergencyStop", loop.isRunning)
        assertTrue("Scope should be cancelled", testScope.coroutineContext.job.isCancelled)
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
        val trigger = Trigger.NotificationReceived("com.example", "Hello", "World")
        val emitted = TriggerBus.tryEmit(trigger)
        assertTrue("tryEmit should succeed with buffer space", emitted)

        // Give the collector a moment to receive the event
        delay(50)
        job.cancel()

        assertTrue("Collector should have received the trigger", collected.contains(trigger))
    }

    @Test
    fun `TriggerBus emit delivers to multiple collectors`(): Unit = runBlocking {
        val collected1 = mutableListOf<Trigger>()
        val collected2 = mutableListOf<Trigger>()

        val job1 = launch { TriggerBus.events.collect { collected1.add(it) } }
        val job2 = launch { TriggerBus.events.collect { collected2.add(it) } }

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
        assertTrue("wasRunning should return true after setRunning(true)", result)
    }

    @Test
    fun `LoopStateStore setRunning false and read back`() {
        every { sharedPrefs.getBoolean("was_running", false) } returns false

        LoopStateStore.setRunning(context, false)
        val result = LoopStateStore.wasRunning(context)

        verify { editor.putBoolean("was_running", false) }
        assertFalse("wasRunning should return false after setRunning(false)", result)
    }

    @Test
    fun `LoopStateStore defaults to false on first run`() {
        every { sharedPrefs.getBoolean("was_running", false) } returns false

        val result = LoopStateStore.wasRunning(context)
        assertFalse("Default state should be false", result)
    }

    @Test
    fun `wasRunningBeforeRestart delegates to LoopStateStore`() {
        every { sharedPrefs.getBoolean(AgentLoop.KEY_WAS_RUNNING, false) } returns true

        assertTrue(loop.wasRunningBeforeRestart())
    }

    companion object {
        // Expose for test verification
    }
}

// Extension to expose KEY_WAS_RUNNING from AgentLoop companion in tests
private val AgentLoop.Companion.KEY_WAS_RUNNING: String get() = "was_running"
