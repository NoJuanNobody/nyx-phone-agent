package com.nyx.agent.skill.impl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidVoiceIOBridge(private val context: Context) : VoiceIOBridge {
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // init TTS on first use (lazy)
    private suspend fun ensureTts(): TextToSpeech = suspendCancellableCoroutine { cont ->
        if (isTtsReady && tts != null) { cont.resume(tts!!); return@suspendCancellableCoroutine }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) { isTtsReady = true; cont.resume(tts!!) }
            else cont.resumeWithException(IllegalStateException("TTS init failed: $status"))
        }
    }

    override suspend fun listen(timeoutMs: Long): SttResult = suspendCancellableCoroutine { cont ->
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                recognizer.destroy()
                if (!matches.isNullOrEmpty()) {
                    cont.resume(SttResult(matches[0], scores?.firstOrNull() ?: 1.0f))
                } else {
                    cont.resumeWithException(IllegalStateException("No speech recognized"))
                }
            }
            override fun onError(error: Int) {
                recognizer.destroy()
                cont.resumeWithException(IllegalStateException("STT error code: $error"))
            }
            // no-op stubs for other callbacks
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
        cont.invokeOnCancellation { recognizer.destroy() }
    }

    override suspend fun speak(text: String, config: TtsConfig): Unit = suspendCancellableCoroutine { cont ->
        val ttsInstance = tts ?: run { cont.resumeWithException(IllegalStateException("TTS not initialized")); return@suspendCancellableCoroutine }
        val utteranceId = UUID.randomUUID().toString()
        ttsInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(u: String?) { if (u == utteranceId) cont.resume(Unit) }
            override fun onError(u: String?) { cont.resumeWithException(IllegalStateException("TTS error for utterance $u")) }
            override fun onStart(u: String?) {}
        })
        ttsInstance.setSpeechRate(config.speechRate)
        ttsInstance.setPitch(config.pitch)
        ttsInstance.language = Locale.forLanguageTag(config.language)
        ttsInstance.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null; isTtsReady = false }
}
