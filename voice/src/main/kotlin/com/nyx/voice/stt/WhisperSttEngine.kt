package com.nyx.voice.stt

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold

/**
 * Production STT engine backed by whisper.cpp via JNI.
 *
 * Audio is expected as 16 kHz, 16-bit signed mono PCM.  All chunks from the
 * incoming [Flow] are concatenated into a single buffer before inference so
 * that the model can attend over the full utterance context.
 *
 * The JNI bridge is declared as [whisperTranscribe] and is implemented in
 * `src/main/jni/whisper_jni.cpp`.  The native library is loaded lazily on
 * first use so that unit-test VMs that lack the `.so` can still instantiate
 * and partially exercise this class using mocking.
 */
class WhisperSttEngine : SttEngine {

    companion object {
        /** Sample rate required by the Whisper model (16 kHz). */
        const val SAMPLE_RATE_HZ: Int = 16_000

        private var nativeLibLoaded = false

        /**
         * Attempt to load the JNI library.  Called lazily so that unit tests
         * that mock [whisperTranscribe] can run without native binaries.
         */
        fun tryLoadNative() {
            if (!nativeLibLoaded) {
                runCatching { System.loadLibrary("nyx_voice_jni") }
                    .onSuccess { nativeLibLoaded = true }
            }
        }
    }

    init {
        tryLoadNative()
    }

    /**
     * Accumulates all PCM chunks from [pcmChunks] into a single [ShortArray]
     * and forwards them to the native Whisper inference pipeline.
     *
     * @return Transcription string from the native layer (or `"stub"` when
     *         running with the stub JNI implementation in CI).
     */
    override suspend fun transcribe(pcmChunks: Flow<ShortArray>): String {
        val buffer: ShortArray = pcmChunks.fold(ShortArray(0)) { acc, chunk ->
            acc + chunk
        }
        return whisperTranscribe(buffer, SAMPLE_RATE_HZ)
    }

    /**
     * JNI entry point.  Implemented in `whisper_jni.cpp`.
     *
     * @param samples  16 kHz 16-bit PCM samples for the full utterance.
     * @param sampleRate  Must be [SAMPLE_RATE_HZ] (passed through for validation).
     * @return Transcribed text, or `"stub"` from the CI stub implementation.
     */
    external fun whisperTranscribe(samples: ShortArray, sampleRate: Int): String
}
