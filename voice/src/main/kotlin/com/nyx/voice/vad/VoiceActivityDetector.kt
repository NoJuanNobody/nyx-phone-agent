package com.nyx.voice.vad

/**
 * Contract for a Voice Activity Detector (VAD).
 *
 * A VAD examines a short PCM audio chunk and determines whether it contains
 * human speech or background silence/noise.
 *
 * Implementations range from simple energy-threshold approaches ([EnergyVad])
 * to full neural-network models such as Silero VAD.
 */
interface VoiceActivityDetector {
    /**
     * Returns `true` if [pcmChunk] contains speech, `false` otherwise.
     *
     * @param pcmChunk 16-bit signed PCM samples for a single audio frame.
     *                 Typical frame size is 160–480 samples (10–30 ms at 16 kHz).
     */
    fun isSpeech(pcmChunk: ShortArray): Boolean
}
