package com.nyx.agent.skill.impl

import com.nyx.agent.skill.SkillResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoiceIOSkillTest {

    private lateinit var bridge: VoiceIOBridge
    private lateinit var skill: VoiceIOSkill

    @Before
    fun setUp() {
        bridge = mockk()
        skill = VoiceIOSkill(bridge)
    }

    @Test
    fun `skill metadata is labeled voice_io`() {
        assertEquals("voice_io", skill.name)
        assertTrue(skill.requiredPermissions.contains("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `listen returns transcript and confidence`() = runTest {
        coEvery { bridge.listen(any()) } returns SttResult("hello world", 0.95f)

        val result = skill.execute(mapOf("action" to "listen"))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals("hello world", output["transcript"])
        assertEquals(0.95f, output["confidence"])
    }

    @Test
    fun `listen STT throws returns Failure`() = runTest {
        coEvery { bridge.listen(any()) } throws IllegalStateException("No speech detected")

        val result = skill.execute(mapOf("action" to "listen"))

        assertTrue(result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertTrue(failure.error.contains("STT failed"))
        assertTrue(failure.error.contains("No speech detected"))
    }

    @Test
    fun `speak returns Success with spoken_chars`() = runTest {
        coEvery { bridge.speak(any(), any()) } returns Unit

        val result = skill.execute(mapOf("action" to "speak", "text" to "Hello, world!"))

        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals(13, output["spoken_chars"])
    }

    @Test
    fun `speak TTS throws returns Failure`() = runTest {
        coEvery { bridge.speak(any(), any()) } throws IllegalStateException("TTS not ready")

        val result = skill.execute(mapOf("action" to "speak", "text" to "Hello"))

        assertTrue(result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertTrue(failure.error.contains("TTS failed"))
        assertTrue(failure.error.contains("TTS not ready"))
    }

    @Test
    fun `speak missing text returns Failure`() = runTest {
        val result = skill.execute(mapOf("action" to "speak"))

        assertTrue(result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertTrue(failure.error.contains("'text' is required"))
    }

    @Test
    fun `listen_and_speak calls listen and returns transcript`() = runTest {
        coEvery { bridge.listen(any()) } returns SttResult("tell me more", 0.88f)

        val result = skill.execute(mapOf("action" to "listen_and_speak"))

        coVerify(exactly = 1) { bridge.listen(any()) }
        assertTrue(result is SkillResult.Success)
        val output = (result as SkillResult.Success).output
        assertEquals("tell me more", output["transcript"])
    }

    @Test
    fun `unknown action returns Failure`() = runTest {
        val result = skill.execute(mapOf("action" to "dance"))

        assertTrue(result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertTrue(failure.error.contains("Unknown action 'dance'"))
    }

    @Test
    fun `TtsConfig defaults applied correctly`() = runTest {
        var capturedConfig: TtsConfig? = null
        coEvery { bridge.speak(any(), any()) } answers {
            capturedConfig = secondArg()
        }

        skill.execute(mapOf("action" to "speak", "text" to "test"))

        assertEquals(TtsConfig(speechRate = 1.0f, pitch = 1.0f, language = "en-US"), capturedConfig)
    }

    @Test
    fun `TtsConfig custom values applied correctly`() = runTest {
        var capturedConfig: TtsConfig? = null
        coEvery { bridge.speak(any(), any()) } answers {
            capturedConfig = secondArg()
        }

        skill.execute(mapOf(
            "action" to "speak",
            "text" to "test",
            "speech_rate" to 1.5f,
            "pitch" to 0.8f,
            "language" to "es-ES",
        ))

        assertEquals(TtsConfig(speechRate = 1.5f, pitch = 0.8f, language = "es-ES"), capturedConfig)
    }

    @Test
    fun `listen uses custom timeout_ms`() = runTest {
        coEvery { bridge.listen(5000L) } returns SttResult("quick", 0.9f)

        val result = skill.execute(mapOf("action" to "listen", "timeout_ms" to 5000L))

        coVerify { bridge.listen(5000L) }
        assertTrue(result is SkillResult.Success)
    }

    @Test
    fun `missing action returns Failure`() = runTest {
        val result = skill.execute(emptyMap())

        assertTrue(result is SkillResult.Failure)
        val failure = result as SkillResult.Failure
        assertTrue(failure.error.contains("'action' is required"))
    }
}
