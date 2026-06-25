package com.nyx.voice.stt

import kotlinx.coroutines.flow.Flow

/**
 * Contract for a Speech-To-Text engine.
 *
 * Implementations consume a [Flow] of raw 16-bit PCM audio chunks and return
 * the full transcribed string once the utterance is complete.
 *
 * @see WhisperSttEngine for the production JNI-backed implementation.
 */
interface SttEngine {
    /**
     * Transcribes a stream of PCM audio chunks.
     *
     * @param pcmChunks Flow emitting 16 kHz, 16-bit mono PCM frames.
     * @return The transcribed text for the complete utterance.
     */
    suspend fun transcribe(pcmChunks: Flow<ShortArray>): String
}
