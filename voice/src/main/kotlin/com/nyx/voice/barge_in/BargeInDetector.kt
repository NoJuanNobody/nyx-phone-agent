package com.nyx.voice.barge_in

import com.nyx.voice.vad.VoiceActivityDetector

/**
 * Detects when a user speaks while the agent is producing TTS output.
 * On detection, signals the pipeline to interrupt playback and re-route to STT.
 */
class BargeInDetector(private val vad: VoiceActivityDetector) {
    var onBargeIn: () -> Unit = {}

    /** Feed an audio chunk while TTS is playing. Calls [onBargeIn] if speech detected. */
    fun feed(pcmChunk: ShortArray) {
        if (vad.isSpeech(pcmChunk)) onBargeIn()
    }
}
