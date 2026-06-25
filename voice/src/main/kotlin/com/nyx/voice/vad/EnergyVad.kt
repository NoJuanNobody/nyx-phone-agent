package com.nyx.voice.vad

import kotlin.math.sqrt

/**
 * Energy-threshold Voice Activity Detector.
 *
 * Computes the Root Mean Square (RMS) amplitude of a PCM chunk and classifies
 * it as speech if the RMS exceeds [thresholdRms].  This approach requires no
 * native libraries and runs entirely on the JVM, making it suitable for both
 * production use and pure-JVM unit tests.
 *
 * Typical tuning guidance:
 *  - Quiet microphone noise floor: RMS ≈ 100–200
 *  - Normal conversational speech at arm's length: RMS ≈ 800–3 000
 *  - The default threshold of 500 is a conservative midpoint; adjust based on
 *    device microphone gain and ambient noise level.
 *
 * @param thresholdRms  RMS value above which a chunk is classified as speech.
 *                      Must be positive.
 */
class EnergyVad(val thresholdRms: Float = 500f) : VoiceActivityDetector {

    init {
        require(thresholdRms > 0f) { "thresholdRms must be positive, was $thresholdRms" }
    }

    /**
     * Returns `true` if the RMS energy of [pcmChunk] exceeds [thresholdRms].
     *
     * An empty chunk is always classified as silence.
     */
    override fun isSpeech(pcmChunk: ShortArray): Boolean {
        if (pcmChunk.isEmpty()) return false
        val rms = computeRms(pcmChunk)
        return rms > thresholdRms
    }

    /**
     * Computes the Root Mean Square of the sample amplitudes.
     *
     * Each [Short] sample is treated as a signed 16-bit integer in the range
     * [−32 768, 32 767].
     */
    private fun computeRms(samples: ShortArray): Float {
        var sumOfSquares = 0.0
        for (sample in samples) {
            val s = sample.toDouble()
            sumOfSquares += s * s
        }
        return sqrt(sumOfSquares / samples.size).toFloat()
    }
}
