package com.nyx.voice.tts

/**
 * Contract for a Text-To-Speech engine.
 *
 * Implementations synthesise speech from a text string and expose two
 * interaction modes:
 *
 *  - [synthesize] — returns raw PCM samples for the caller to route/buffer.
 *  - [speak] — convenience method that synthesises **and** plays audio through
 *    the device speaker immediately.
 *
 * @see KokoroTtsEngine for the production JNI-backed implementation.
 */
interface TtsEngine {
    /**
     * Synthesises [text] into 16-bit PCM audio samples.
     *
     * @return Raw 16-bit signed mono PCM samples at the engine's native
     *         sample rate (typically 22 050 Hz for Kokoro).
     */
    suspend fun synthesize(text: String): ShortArray

    /**
     * Synthesises [text] and immediately plays it through the device speaker.
     *
     * This is a convenience wrapper; implementations may pipeline synthesis
     * and playback to reduce perceived latency.
     */
    suspend fun speak(text: String)
}
