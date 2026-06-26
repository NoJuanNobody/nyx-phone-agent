package com.nyx.voice.wakeword

/**
 * Contract for a wake-word / keyword-spotting engine.
 *
 * A wake-word engine scans incoming PCM audio and signals when a configured
 * activation phrase (e.g. "Hey Nyx") has been detected.
 *
 * Production implementations may use dedicated on-device keyword spotting
 * libraries such as Picovoice Porcupine.  The [KeywordSpottingWakeWord]
 * implementation provides a lightweight transcript-scan stub.
 */
interface WakeWordEngine {
    /**
     * Returns `true` if the wake word was detected in [pcmChunk].
     *
     * @param pcmChunk 16 kHz 16-bit mono PCM samples for a single audio frame.
     */
    fun detect(pcmChunk: ShortArray): Boolean
}
