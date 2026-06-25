package com.nyx.voice.barge_in

import com.nyx.voice.vad.VoiceActivityDetector
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class BargeInDetectorTest {

    private val speechChunk = ShortArray(160) { 1_000.toShort() }
    private val silentChunk = ShortArray(160) { 0 }

    @Test
    fun `onBargeIn is invoked when VAD detects speech`() {
        val vad = mockk<VoiceActivityDetector> {
            every { isSpeech(any()) } returns true
        }
        val detector = BargeInDetector(vad)

        var callCount = 0
        detector.onBargeIn = { callCount++ }

        detector.feed(speechChunk)

        assertEquals("onBargeIn should have been called once", 1, callCount)
    }

    @Test
    fun `onBargeIn is NOT invoked when VAD returns false`() {
        val vad = mockk<VoiceActivityDetector> {
            every { isSpeech(any()) } returns false
        }
        val detector = BargeInDetector(vad)

        var callCount = 0
        detector.onBargeIn = { callCount++ }

        detector.feed(silentChunk)

        assertEquals("onBargeIn should not have been called", 0, callCount)
    }

    @Test
    fun `multiple silent chunks never trigger onBargeIn`() {
        val vad = mockk<VoiceActivityDetector> {
            every { isSpeech(any()) } returns false
        }
        val detector = BargeInDetector(vad)

        var callCount = 0
        detector.onBargeIn = { callCount++ }

        repeat(10) { detector.feed(silentChunk) }

        assertEquals(0, callCount)
    }

    @Test
    fun `multiple speech chunks each trigger onBargeIn`() {
        val vad = mockk<VoiceActivityDetector> {
            every { isSpeech(any()) } returns true
        }
        val detector = BargeInDetector(vad)

        var callCount = 0
        detector.onBargeIn = { callCount++ }

        repeat(5) { detector.feed(speechChunk) }

        assertEquals("Each speech chunk should trigger onBargeIn", 5, callCount)
    }

    @Test
    fun `default onBargeIn callback does not throw`() {
        val vad = mockk<VoiceActivityDetector> {
            every { isSpeech(any()) } returns true
        }
        val detector = BargeInDetector(vad)

        // Should not throw even with the default no-op callback.
        detector.feed(speechChunk)
    }

    @Test
    fun `onBargeIn callback can be replaced at runtime`() {
        val vad = mockk<VoiceActivityDetector> {
            every { isSpeech(any()) } returns true
        }
        val detector = BargeInDetector(vad)

        var firstCallCount = 0
        var secondCallCount = 0

        detector.onBargeIn = { firstCallCount++ }
        detector.feed(speechChunk)

        detector.onBargeIn = { secondCallCount++ }
        detector.feed(speechChunk)

        assertEquals(1, firstCallCount)
        assertEquals(1, secondCallCount)
    }
}
