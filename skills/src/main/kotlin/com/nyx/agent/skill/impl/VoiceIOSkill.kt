package com.nyx.agent.skill.impl

import com.nyx.agent.skill.Skill
import com.nyx.agent.skill.SkillResult

enum class TriggerMode { MANUAL, WAKEWORD }

data class TtsConfig(
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val language: String = "en-US",
)

data class SttResult(
    val transcript: String,
    val confidence: Float,
)

/**
 * Abstraction over Android SpeechRecognizer and TextToSpeech for testability.
 */
interface VoiceIOBridge {
    /** Start listening and return the transcription. Throws on failure. */
    suspend fun listen(timeoutMs: Long = 10_000): SttResult
    /** Speak [text] using [config]. Suspends until speech completes. */
    suspend fun speak(text: String, config: TtsConfig)
    /** Shut down TTS/STT resources. */
    fun shutdown()
}

/**
 * Skill for voice input/output.
 *
 * Actions:
 * - `listen` — trigger STT and return transcript
 * - `speak` — synthesize text as speech
 * - `listen_and_speak` — listen, return transcript (caller can then call speak with response)
 *
 * Args:
 * - `action` (String): "listen" | "speak" | "listen_and_speak"
 * - `text` (String): text to speak — required for "speak"
 * - `timeout_ms` (Long, default 10000): STT timeout
 * - `speech_rate` (Float, default 1.0)
 * - `pitch` (Float, default 1.0)
 * - `language` (String, default "en-US")
 * - `trigger_mode` (String, default "manual"): "manual" | "wakeword"
 */
class VoiceIOSkill(private val bridge: VoiceIOBridge) : Skill {
    override val name = "voice_io"
    override val description = "Listen for speech via STT or synthesize text via TTS"
    override val requiredPermissions = listOf("android.permission.RECORD_AUDIO")

    override suspend fun execute(args: Map<String, Any>): SkillResult {
        val action = args["action"] as? String
            ?: return SkillResult.Failure("'action' is required: listen, speak, listen_and_speak")
        val timeoutMs = (args["timeout_ms"] as? Number)?.toLong() ?: 10_000L
        val ttsConfig = TtsConfig(
            speechRate = (args["speech_rate"] as? Number)?.toFloat() ?: 1.0f,
            pitch = (args["pitch"] as? Number)?.toFloat() ?: 1.0f,
            language = args["language"] as? String ?: "en-US",
        )

        return when (action) {
            "listen" -> doListen(timeoutMs)
            "speak" -> {
                val text = args["text"] as? String
                    ?: return SkillResult.Failure("'text' is required for speak")
                doSpeak(text, ttsConfig)
            }
            "listen_and_speak" -> doListen(timeoutMs)  // caller speaks after receiving transcript
            else -> SkillResult.Failure("Unknown action '$action'. Use listen, speak, or listen_and_speak")
        }
    }

    private suspend fun doListen(timeoutMs: Long): SkillResult {
        return try {
            val result = bridge.listen(timeoutMs)
            SkillResult.Success(mapOf(
                "transcript" to result.transcript,
                "confidence" to result.confidence,
            ))
        } catch (e: Exception) {
            SkillResult.Failure("STT failed: ${e.message}", e)
        }
    }

    private suspend fun doSpeak(text: String, config: TtsConfig): SkillResult {
        return try {
            bridge.speak(text, config)
            SkillResult.Success(mapOf("spoken_chars" to text.length))
        } catch (e: Exception) {
            SkillResult.Failure("TTS failed: ${e.message}", e)
        }
    }
}
