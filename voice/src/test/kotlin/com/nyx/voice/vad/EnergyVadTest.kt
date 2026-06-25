package com.nyx.voice.vad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class EnergyVadTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Helper factories
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates a [ShortArray] whose RMS is approximately [targetRms].
     *
     * For a sine-wave signal of amplitude A, RMS = A / sqrt(2).
     * We use a constant (DC) signal here so that RMS == amplitude exactly,
     * making test construction straightforward.
     */
    private fun chunkWithRms(targetRms: Float, size: Int = 160): ShortArray {
        val amplitude = targetRms.roundToInt().toShort()
        return ShortArray(size) { amplitude }
    }

    private fun silentChunk(size: Int = 160): ShortArray = ShortArray(size) { 0 }

    // ──────────────────────────────────────────────────────────────────────────
    // Tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `chunk above threshold is classified as speech`() {
        val vad = EnergyVad(thresholdRms = 500f)
        val loudChunk = chunkWithRms(targetRms = 1_000f)
        assertTrue("Loud chunk should be classified as speech", vad.isSpeech(loudChunk))
    }

    @Test
    fun `silent chunk is classified as silence`() {
        val vad = EnergyVad(thresholdRms = 500f)
        assertFalse("Silent chunk should not be speech", vad.isSpeech(silentChunk()))
    }

    @Test
    fun `chunk exactly at threshold is classified as silence`() {
        // Boundary: RMS equal to threshold → NOT speech (strictly greater than required).
        val vad = EnergyVad(thresholdRms = 500f)
        val borderlineChunk = chunkWithRms(targetRms = 500f)
        assertFalse(
            "Chunk at threshold boundary should not be speech",
            vad.isSpeech(borderlineChunk)
        )
    }

    @Test
    fun `chunk just above threshold is classified as speech`() {
        val vad = EnergyVad(thresholdRms = 500f)
        val justAboveChunk = chunkWithRms(targetRms = 501f)
        assertTrue(vad.isSpeech(justAboveChunk))
    }

    @Test
    fun `empty chunk is classified as silence`() {
        val vad = EnergyVad(thresholdRms = 500f)
        assertFalse("Empty chunk should be silence", vad.isSpeech(ShortArray(0)))
    }

    @Test
    fun `configurable threshold — low threshold detects quiet audio`() {
        val vad = EnergyVad(thresholdRms = 50f)
        val quietChunk = chunkWithRms(targetRms = 100f)
        assertTrue("Quiet chunk above low threshold should be speech", vad.isSpeech(quietChunk))
    }

    @Test
    fun `configurable threshold — high threshold ignores moderate audio`() {
        val vad = EnergyVad(thresholdRms = 5_000f)
        val moderateChunk = chunkWithRms(targetRms = 1_000f)
        assertFalse(
            "Moderate audio should not pass a very high threshold",
            vad.isSpeech(moderateChunk)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative threshold throws IllegalArgumentException`() {
        EnergyVad(thresholdRms = -1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero threshold throws IllegalArgumentException`() {
        EnergyVad(thresholdRms = 0f)
    }
}
