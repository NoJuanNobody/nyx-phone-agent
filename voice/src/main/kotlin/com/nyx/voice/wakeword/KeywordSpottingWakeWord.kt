package com.nyx.voice.wakeword

import com.nyx.voice.stt.WhisperSttEngine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Lightweight keyword-spotting wake-word engine.
 *
 * Strategy:
 *   1. Mini-transcribes each incoming PCM chunk using [WhisperSttEngine].
 *   2. Scans the resulting transcript for the activation phrase "hey nyx"
 *      (case-insensitive).
 *
 * This is a stub-level implementation intended for CI testing and early
 * integration.  It is not optimised for real-time operation because running a
 * full Whisper inference pass on every small audio frame is expensive.
 *
 * Production path: replace with [Picovoice Porcupine](https://picovoice.ai/),
 * which runs a tiny on-device neural keyword-spotter at <1 % CPU.
 *
 * @param stt  STT engine used for mini-transcription.  Injected to allow
 *             mocking in unit tests.
 * @param keyword  Wake phrase to scan for; defaults to `"hey nyx"`.
 */
class KeywordSpottingWakeWord(
    private val stt: WhisperSttEngine = WhisperSttEngine(),
    private val keyword: String = "hey nyx",
) : WakeWordEngine {

    /**
     * Returns `true` if the transcription of [pcmChunk] contains [keyword]
     * (case-insensitive match).
     *
     * The transcription is performed synchronously via [runBlocking] because
     * [WakeWordEngine.detect] is a synchronous interface.  Callers should
     * invoke this from a background thread to avoid blocking the audio
     * callback thread.
     */
    override fun detect(pcmChunk: ShortArray): Boolean {
        val transcript = runBlocking {
            stt.transcribe(flowOf(pcmChunk))
        }
        return transcript.contains(keyword, ignoreCase = true)
    }
}
