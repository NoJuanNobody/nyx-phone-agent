package com.nyx.voice.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Production TTS engine backed by the Kokoro (or Piper) ONNX model via JNI.
 *
 * [synthesize] calls into `kokoro_jni.cpp` which invokes the native inference
 * engine and returns 22 050 Hz 16-bit mono PCM samples.  [speak] plays those
 * samples through [AudioTrack] in streaming mode.
 *
 * @param voiceId  Index of the pre-loaded voice in the Kokoro model bundle.
 *                 Defaults to `0` (first bundled voice).
 */
class KokoroTtsEngine(private val voiceId: Int = 0) : TtsEngine {

    companion object {
        /** Native output sample rate of the Kokoro model. */
        const val SAMPLE_RATE_HZ: Int = 22_050

        private var nativeLibLoaded = false

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
     * Calls the native Kokoro inference pipeline and returns raw PCM samples.
     *
     * Returns an empty [ShortArray] when running with the CI stub.
     */
    override suspend fun synthesize(text: String): ShortArray {
        return kokoroSynthesize(text, voiceId)
    }

    /**
     * Synthesises [text] and plays it synchronously through the device speaker
     * using [AudioTrack] in [AudioTrack.MODE_STATIC] mode.
     *
     * For streaming / low-latency playback a [AudioTrack.MODE_STREAM]
     * implementation should be preferred in production; this version keeps
     * the implementation minimal and easy to unit-test.
     */
    override suspend fun speak(text: String) {
        val samples = synthesize(text)
        if (samples.isEmpty()) return

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(samples, 0, samples.size)
        audioTrack.play()

        // Block until playback completes before releasing the track.
        val durationMs = (samples.size.toLong() * 1_000L) / SAMPLE_RATE_HZ
        kotlinx.coroutines.delay(durationMs)
        audioTrack.stop()
        audioTrack.release()
    }

    /**
     * JNI entry point.  Implemented in `kokoro_jni.cpp`.
     *
     * @param text     UTF-8 text to synthesise.
     * @param voiceId  Index of the voice in the model bundle.
     * @return 22 050 Hz 16-bit mono PCM samples, or empty array from the stub.
     */
    external fun kokoroSynthesize(text: String, voiceId: Int): ShortArray
}
