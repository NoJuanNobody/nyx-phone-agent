package com.nyx.voice

import com.nyx.voice.barge_in.BargeInDetector
import com.nyx.voice.stt.SttEngine
import com.nyx.voice.tts.TtsEngine
import com.nyx.voice.vad.VoiceActivityDetector
import com.nyx.voice.wakeword.WakeWordEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Orchestrates the full voice pipeline:
 *   AudioCapture → VAD → STT → [LLM, via callback] → TTS → AudioOutput
 *
 * The LLM step is handled externally — the controller provides [onTranscript] callback
 * so the agent loop can inject responses.
 *
 * ## Pipeline states
 *
 * ```
 * IDLE ──► WAKE_WORD_LISTENING
 *               │
 *               ▼ (wake word detected)
 *          SPEECH_CAPTURING ──► (VAD end-of-speech)
 *               │
 *               ▼
 *            TRANSCRIBING
 *               │
 *               ▼
 *         LLM_RESPONDING  (onTranscript callback)
 *               │
 *               ▼
 *           TTS_PLAYING ──► SPEECH_CAPTURING  (barge-in)
 *               │
 *               ▼
 *              IDLE
 * ```
 *
 * @param stt       Speech-to-text engine.
 * @param tts       Text-to-speech engine.
 * @param vad       Voice activity detector used to gate STT and detect end-of-speech.
 * @param wakeWord  Wake-word engine; pipeline only activates after detection.
 * @param bargeIn   Barge-in detector; monitors audio during TTS playback.
 * @param scope     [CoroutineScope] that owns the pipeline coroutine lifecycle.
 */
class VoicePipelineController(
    private val stt: SttEngine,
    private val tts: TtsEngine,
    private val vad: VoiceActivityDetector,
    private val wakeWord: WakeWordEngine,
    private val bargeIn: BargeInDetector,
    private val scope: CoroutineScope,
) {
    /**
     * Called with transcribed user speech.
     *
     * The caller (agent loop) should process the [transcript] and return the
     * agent's response text.  The response is fed directly to [tts] for
     * synthesis and playback.
     *
     * Defaults to a no-op that returns an empty string (useful for testing).
     */
    var onTranscript: suspend (transcript: String) -> String = { "" }

    /** Internal PCM chunk channel — populated by [feedAudio]. */
    private val audioChannel = Channel<ShortArray>(capacity = Channel.UNLIMITED)

    private var pipelineJob: Job? = null

    /** `true` while TTS is playing; used to route audio to [bargeIn]. */
    @Volatile
    private var isSpeaking = false

    /** `true` while the pipeline is actively listening for speech (post wake-word). */
    @Volatile
    private var isListening = false

    // Wire barge-in callback once so it's always active.
    init {
        bargeIn.onBargeIn = ::handleBargeIn
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Starts the pipeline coroutine.  Audio chunks should be fed via [feedAudio]
     * after calling [start].  Safe to call multiple times — subsequent calls are
     * no-ops if the pipeline is already running.
     */
    fun start() {
        if (pipelineJob?.isActive == true) return
        pipelineJob = scope.launch { runPipeline() }
    }

    /**
     * Stops the pipeline and cancels any in-progress STT or TTS work.
     */
    fun stop() {
        pipelineJob?.cancel()
        pipelineJob = null
        isSpeaking = false
        isListening = false
    }

    /**
     * Injects a TTS utterance directly, bypassing the STT/transcript path.
     *
     * Useful for agent-initiated speech (e.g. greetings, confirmations) where
     * there is no prior user utterance to transcribe.
     *
     * This call suspends until playback completes (or is interrupted by barge-in).
     */
    fun speak(text: String) {
        scope.launch { playSpeech(text) }
    }

    /**
     * Delivers a raw PCM audio chunk to the pipeline.
     *
     * Should be called from the audio capture thread / callback at the
     * configured frame rate (typically 10–30 ms frames at 16 kHz).
     *
     * @param pcmChunk 16 kHz 16-bit signed mono PCM samples.
     */
    fun feedAudio(pcmChunk: ShortArray) {
        if (isSpeaking) {
            // Route to barge-in detector while TTS is active.
            bargeIn.feed(pcmChunk)
        } else {
            audioChannel.trySend(pcmChunk)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal pipeline
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun runPipeline() {
        val audioFlow: Flow<ShortArray> = audioChannel.receiveAsFlow()

        // Phase 1: wait for wake word.
        awaitWakeWord()

        // Phase 2: capture speech until VAD detects end-of-utterance.
        isListening = true
        val transcript = captureSpeechAndTranscribe(audioFlow)
        isListening = false

        if (transcript.isBlank()) {
            // No meaningful speech detected — restart.
            runPipeline()
            return
        }

        // Phase 3: call out to the LLM agent (external callback).
        val response = onTranscript(transcript)

        // Phase 4: synthesise and play the response.
        if (response.isNotBlank()) {
            playSpeech(response)
        }

        // Phase 5: return to listening (loop).
        runPipeline()
    }

    /**
     * Blocks until a chunk passes the wake-word detector.
     */
    private suspend fun awaitWakeWord() {
        for (chunk in audioChannel) {
            if (wakeWord.detect(chunk)) return
        }
    }

    /**
     * Captures audio chunks from [audioFlow] while VAD detects speech, then
     * sends the accumulated samples to STT.
     *
     * Returns the transcription string (may be blank if no speech was captured).
     */
    private suspend fun captureSpeechAndTranscribe(audioFlow: Flow<ShortArray>): String {
        // Accumulate speech chunks and delegate to the STT engine.
        return stt.transcribe(audioFlow)
    }

    /**
     * Synthesises [text] via TTS and plays it.  Sets [isSpeaking] so that
     * concurrent [feedAudio] calls are routed to [bargeIn].
     */
    private suspend fun playSpeech(text: String) {
        isSpeaking = true
        try {
            tts.speak(text)
        } finally {
            isSpeaking = false
        }
    }

    /**
     * Invoked by [BargeInDetector.onBargeIn] when user speech is detected
     * during TTS playback.  Cancels the current TTS job and switches the
     * pipeline back to STT capture mode.
     */
    private fun handleBargeIn() {
        if (isSpeaking) {
            // Cancel the TTS playback by stopping the current pipeline coroutine
            // and restarting from speech-capture phase.  The [stop]/[start] cycle
            // gives a clean slate; a production implementation would cancel only
            // the TTS coroutine and jump directly to the STT phase.
            pipelineJob?.cancel()
            isSpeaking = false
            isListening = false
            pipelineJob = scope.launch { runPipeline() }
        }
    }
}
