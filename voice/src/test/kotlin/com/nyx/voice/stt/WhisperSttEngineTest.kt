package com.nyx.voice.stt

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method

/**
 * Unit tests for [WhisperSttEngine].
 *
 * The native JNI library is unavailable in the JVM test environment.  Tests
 * that exercise transcription mock [WhisperSttEngine.whisperTranscribe] so that
 * no native code is invoked.
 */
class WhisperSttEngineTest {

    // ──────────────────────────────────────────────────────────────────────────
    // JNI signature tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `whisperTranscribe method exists with correct signature`() {
        // Verify via reflection that the JNI method declaration is present and
        // has the exact parameter types the C++ stub expects.
        val method: Method = WhisperSttEngine::class.java.getDeclaredMethod(
            "whisperTranscribe",
            ShortArray::class.java,
            Int::class.java,
        )
        assertEquals(
            "Return type must be String",
            String::class.java,
            method.returnType,
        )
    }

    @Test
    fun `SAMPLE_RATE_HZ constant is 16000`() {
        assertEquals(16_000, WhisperSttEngine.SAMPLE_RATE_HZ)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Transcription behaviour (JNI mocked)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `transcribe returns stub string from mocked JNI call`() = runBlocking {
        val engine = spyk(WhisperSttEngine())

        // Stub the native call so no actual JNI dispatch occurs.
        every { engine.whisperTranscribe(any(), any()) } returns "stub"

        val pcmChunk = ShortArray(160) { 500.toShort() }
        val result = engine.transcribe(flowOf(pcmChunk))

        assertEquals("stub", result)
        verify(exactly = 1) { engine.whisperTranscribe(any(), WhisperSttEngine.SAMPLE_RATE_HZ) }
    }

    @Test
    fun `transcribe concatenates multiple chunks before calling JNI`() = runBlocking {
        val engine = spyk(WhisperSttEngine())

        var capturedSamples: ShortArray? = null
        every { engine.whisperTranscribe(any(), any()) } answers {
            capturedSamples = firstArg()
            "concatenated"
        }

        val chunk1 = ShortArray(160) { 1 }
        val chunk2 = ShortArray(160) { 2 }

        val result = engine.transcribe(flowOf(chunk1, chunk2))

        assertEquals("concatenated", result)
        assertEquals(
            "Both chunks should be concatenated into a single buffer",
            320,
            capturedSamples?.size,
        )
    }

    @Test
    fun `transcribe passes SAMPLE_RATE_HZ to native`() = runBlocking {
        val engine = spyk(WhisperSttEngine())
        every { engine.whisperTranscribe(any(), any()) } returns "ok"

        engine.transcribe(flowOf(ShortArray(160)))

        verify { engine.whisperTranscribe(any(), WhisperSttEngine.SAMPLE_RATE_HZ) }
    }

    @Test
    fun `transcribe handles empty flow`() = runBlocking {
        val engine = spyk(WhisperSttEngine())
        every { engine.whisperTranscribe(any(), any()) } returns ""

        val result = engine.transcribe(flowOf())

        assertEquals("", result)
        verify(exactly = 1) { engine.whisperTranscribe(ShortArray(0), WhisperSttEngine.SAMPLE_RATE_HZ) }
    }
}
