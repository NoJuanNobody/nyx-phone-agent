package com.nyx.agent.launcher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.nyx.agent.agent.ConversationalAgent
import com.nyx.agent.daemon.DaemonLifecycleManager
import com.nyx.agent.launcher.apps.BuildAppSkill
import com.nyx.agent.launcher.apps.ListAppsSkill
import com.nyx.agent.launcher.apps.OpenAppSkill
import com.nyx.agent.launcher.apps.PinAppSkill
import com.nyx.agent.skill.SkillRegistry
import com.nyx.agent.skill.impl.AndroidSmsBridge
import com.nyx.agent.skill.impl.AndroidSystemControlsBridge
import com.nyx.agent.skill.impl.AndroidVoiceIOBridge
import com.nyx.agent.skill.impl.LaunchAppSkill
import com.nyx.agent.skill.impl.SmsSkill
import com.nyx.agent.skill.impl.SystemControlsSkill
import com.nyx.agent.skill.impl.VoiceIOSkill
import com.nyx.llm.backends.OpenRouterLlmBackend
import com.nyx.llm.chat.OpenRouterChatClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Chat screen for talking to Nyx. You type (or speak) a message; Nyx replies in natural
 * language and calls skills as tools when an action is wanted (see [ConversationalAgent]).
 * Replies are spoken aloud via [TextToSpeech]; the mic uses [SpeechRecognizer].
 *
 * API key + model are configured up top and persisted in SharedPreferences "nyx".
 */
class MainActivity : Activity() {

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var apiKeyField: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var messagesView: LinearLayout
    private lateinit var conversation: ScrollView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var daemonStatus: TextView

    private val registry by lazy { buildRegistry(applicationContext) }
    private var agent: ConversationalAgent? = null
    private var agentKey: String = ""
    private var agentModel: String = ""

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakReplies = true
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("nyx", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setPadding(dp(14), dp(28), dp(14), dp(10))
        }

        // ---- header + daemon status ----
        root.addView(TextView(this).apply {
            text = "Nyx"; textSize = 24f; setTypeface(typeface, Typeface.BOLD)
        })
        daemonStatus = TextView(this).apply { textSize = 11f; setTextColor(Color.GRAY) }
        root.addView(daemonStatus)

        // ---- settings: key + model + controls ----
        apiKeyField = EditText(this).apply {
            layoutParams = fullWidth()
            setText(prefs.getString("api_key", "").orEmpty())
            hint = "OpenRouter API key (sk-or-...)"
            textSize = 12f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        root.addView(apiKeyField)

        val settingsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, OPENROUTER_MODELS
            )
            setSelection(OPENROUTER_MODELS.indexOf(prefs.getString("model", DEFAULT_MODEL)).coerceAtLeast(0))
        }
        settingsRow.addView(modelSpinner, weight = 1f)
        settingsRow.addView(button("Speak: on") { toggleSpeak(it as Button) }, weight = 0f)
        settingsRow.addView(button("New") { newChat() }, weight = 0f)
        root.addView(settingsRow)

        // ---- conversation ----
        messagesView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        conversation = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(messagesView)
        }
        root.addView(conversation)

        // ---- input row: text + mic + send ----
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputField = EditText(this).apply {
            hint = "Message Nyx… (e.g. open Chrome)"
            textSize = 14f
            maxLines = 3
        }
        inputRow.addView(inputField, weight = 1f)
        inputRow.addView(button("🎤") { startVoiceInput() }, weight = 0f)
        sendButton = button("Send") { onSend(inputField.text.toString()) }
        inputRow.addView(sendButton, weight = 0f)
        root.addView(inputRow)

        setContentView(root)

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }

        ensureNotificationPermission()
        DaemonLifecycleManager.start(this)
        refreshDaemon()
        addBubble("assistant", "Hi, I'm Nyx. Ask me to open an app, change a setting, or just chat.")

        intent?.getStringExtra("nyx_cmd")?.let { onSend(it) }
    }

    override fun onResume() { super.onResume(); refreshDaemon() }

    override fun onDestroy() {
        ui.cancel()
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    private fun onSend(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        val apiKey = apiKeyField.text.toString().trim()
        if (apiKey.isEmpty()) { addBubble("assistant", "Add your OpenRouter API key up top first."); return }

        val model = (modelSpinner.selectedItem as? String) ?: DEFAULT_MODEL
        prefs.edit().putString("api_key", apiKey).putString("model", model).apply()

        addBubble("user", message)
        inputField.setText("")
        sendButton.isEnabled = false
        val thinking = addBubble("assistant", "…")

        ui.launch {
            val reply = runCatching { ensureAgent(apiKey, model).send(message) }
                .getOrElse { e -> "Sorry, something went wrong: ${e.message}" }
            messagesView.removeView(thinking)
            addBubble("assistant", reply)
            speak(reply)
            sendButton.isEnabled = true
        }
    }

    private fun ensureAgent(apiKey: String, model: String): ConversationalAgent {
        if (agent == null || agentKey != apiKey || agentModel != model) {
            agent = ConversationalAgent(OpenRouterChatClient(apiKey), model, registry)
            agentKey = apiKey
            agentModel = model
        }
        return agent!!
    }

    private fun newChat() {
        agent?.reset()
        messagesView.removeAllViews()
        addBubble("assistant", "New chat. What can I do for you?")
    }

    private fun buildRegistry(context: Context): SkillRegistry = SkillRegistry().apply {
        register(LaunchAppSkill(context))
        register(SystemControlsSkill(AndroidSystemControlsBridge(context)))
        register(SmsSkill(AndroidSmsBridge(context)))
        register(VoiceIOSkill(AndroidVoiceIOBridge(context)))
        // Nyx can build & run mini-apps on the phone; code-gen runs through the selected LLM.
        register(BuildAppSkill(context) { spec -> generateAppHtml(spec) })
        register(OpenAppSkill(context))
        register(ListAppsSkill(context))
        register(PinAppSkill(context))
    }

    /** Generates a complete self-contained HTML mini-app for [spec] using the selected model. */
    private suspend fun generateAppHtml(spec: String): String {
        val apiKey = apiKeyField.text.toString().trim()
        val model = (modelSpinner.selectedItem as? String) ?: DEFAULT_MODEL
        val engine = OpenRouterLlmBackend(apiKey = apiKey, model = model, systemPrompt = CODE_SYSTEM_PROMPT)
        return engine.generate("Build this app:\n$spec", maxTokens = 4000)
    }

    // -------------------------------------------------------------------------
    // Voice
    // -------------------------------------------------------------------------

    private fun speak(text: String) {
        if (speakReplies && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nyx-reply")
        }
    }

    private fun toggleSpeak(btn: Button) {
        speakReplies = !speakReplies
        btn.text = if (speakReplies) "Speak: on" else "Speak: off"
        if (!speakReplies) tts?.stop()
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition isn't available on this device.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { inputField.hint = "Listening…" }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    inputField.hint = "Message Nyx…"
                    if (text.isNotBlank()) onSend(text)
                }
                override fun onError(error: Int) {
                    inputField.hint = "Message Nyx…"
                    toast("Voice input error ($error).")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
        }
        recognizer?.startListening(intent)
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    /** Adds a chat bubble and returns the view (so a placeholder can be replaced/removed). */
    private fun addBubble(role: String, text: String): View {
        val isUser = role == "user"
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextColor(if (isUser) Color.WHITE else Color.parseColor("#101418"))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (isUser) Color.parseColor("#3D5AFE") else Color.parseColor("#E8EAF0"))
            }
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(4); bottomMargin = dp(4)
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        messagesView.addView(bubble, lp)
        conversation.post { conversation.fullScroll(View.FOCUS_DOWN) }
        return bubble
    }

    private fun refreshDaemon() {
        val running = DaemonLifecycleManager.isRunning(this)
        daemonStatus.text = if (running) "● daemon running" else "○ daemon stopped"
        daemonStatus.setTextColor(if (running) Color.parseColor("#2E7D32") else Color.GRAY)
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick(it) }
    }

    private fun LinearLayout.addView(view: View, weight: Float) {
        val width = if (weight > 0f) 0 else WRAP_CONTENT
        addView(view, LinearLayout.LayoutParams(width, WRAP_CONTENT, weight))
    }

    private fun fullWidth() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_MODEL = "openai/gpt-4o-mini"

        val CODE_SYSTEM_PROMPT = """
            You are an expert front-end engineer. You output a COMPLETE, self-contained, single-file
            HTML document with inline <style> and <script> — no external resources, no CDNs, no network
            calls — that runs offline inside an Android WebView. Design mobile-first: large touch
            targets, readable fonts, a clean modern look, and use localStorage if persistence helps.
            Output ONLY the HTML, starting with <!DOCTYPE html>. No markdown fences, no commentary.
        """.trimIndent()

        val OPENROUTER_MODELS = listOf(
            "openai/gpt-4o-mini",
            "openai/gpt-4o",
            "anthropic/claude-3.7-sonnet",
            "anthropic/claude-3.5-sonnet",
            "anthropic/claude-3.5-haiku",
            "google/gemini-2.0-flash-001",
            "google/gemini-flash-1.5",
            "meta-llama/llama-3.3-70b-instruct",
            "deepseek/deepseek-chat",
            "mistralai/mistral-small-24b-instruct-2501",
            "qwen/qwen-2.5-72b-instruct",
            "x-ai/grok-2-1212",
        )
    }
}
