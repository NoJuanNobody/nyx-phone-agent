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
import android.view.ViewGroup
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
import android.net.Uri
import com.nyx.agent.agent.ConversationalAgent
import com.nyx.agent.agent.ExternalTool
import com.nyx.agent.daemon.DaemonLifecycleManager
import com.nyx.agent.launcher.mcp.McpOAuth
import com.nyx.agent.mcpclient.RemoteMcpClient
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import java.util.UUID

/**
 * Terminal-styled chat screen for talking to Nyx: green-on-black, monospace, prompt-style
 * input. You type (or speak) a message; Nyx replies in natural language and calls skills as
 * tools (see [ConversationalAgent]). Replies are spoken via [TextToSpeech]; the mic uses
 * [SpeechRecognizer]. API key + model are configured up top and persisted in prefs "nyx".
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

    private lateinit var mcpUrlField: EditText
    private val registry by lazy { buildRegistry(applicationContext) }
    private var agent: ConversationalAgent? = null
    private var agentKey: String = ""
    private var agentModel: String = ""
    private var mcpTools: List<ExternalTool> = emptyList()
    private val oauth by lazy { McpOAuth(prefs) }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakReplies = true
    private var verbose = true   // show [tool] trace lines
    private var recognizer: SpeechRecognizer? = null

    // Terminal palette
    private val cBg = Color.parseColor("#0A0F0A")
    private val cFg = Color.parseColor("#2EE66B")      // phosphor green (assistant/output)
    private val cUser = Color.parseColor("#CFFFD8")    // brighter green-white (user input)
    private val cDim = Color.parseColor("#3C8F5E")     // dim green (chrome, hints)
    private val cErr = Color.parseColor("#FFB300")     // amber (errors)
    private val cTool = Color.parseColor("#56B6C2")    // cyan (tool-call trace)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("nyx", Context.MODE_PRIVATE)
        window.statusBarColor = cBg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(cBg)
            setPadding(dp(12), dp(28), dp(12), dp(8))
        }

        // ---- banner + status ----
        root.addView(term("nyx://terminal", cFg, 18f, bold = true))
        daemonStatus = term("", cDim, 11f)
        root.addView(daemonStatus)

        // ---- settings: key + model + controls ----
        apiKeyField = termField(
            prefill = prefs.getString("api_key", "").orEmpty(),
            hint = "openrouter api key (sk-or-...)",
            password = true,
        )
        root.addView(apiKeyField)

        modelSpinner = Spinner(this).apply {
            adapter = terminalSpinnerAdapter()
            layoutParams = fullWidth()
        }
        modelSpinner.setSelection(OPENROUTER_MODELS.indexOf(prefs.getString("model", DEFAULT_MODEL)).coerceAtLeast(0))
        root.addView(modelSpinner)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("[tts:on]") { toggleSpeak(it as Button) }, weight = 0f)
        controls.addView(button("[trace:on]") { toggleTrace(it as Button) }, weight = 0f)
        controls.addView(button("[new]") { newChat() }, weight = 0f)
        root.addView(controls)

        // MCP server connection (OAuth login + tool discovery)
        mcpUrlField = termField(
            prefill = prefs.getString("mcp_url", DEFAULT_MCP_URL).orEmpty(),
            hint = "mcp server url",
            password = false,
        )
        root.addView(mcpUrlField)
        val mcpRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mcpRow.addView(button("[mcp:connect]") { connectMcp() }, weight = 0f)
        mcpRow.addView(button("[mcp:logout]") { logoutMcp() }, weight = 0f)
        root.addView(mcpRow)

        // separator
        root.addView(term("────────────────────────────", cDim, 12f))

        // ---- conversation ----
        messagesView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        conversation = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(messagesView)
        }
        root.addView(conversation)

        // ---- prompt input row: > [field] [mic] [run] ----
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(term(">", cFg, 16f, bold = true).apply { setPadding(0, 0, dp(6), 0) }, weight = 0f)
        inputField = termField(prefill = "", hint = "type a command…", password = false).apply { maxLines = 3 }
        inputRow.addView(inputField, weight = 1f)
        inputRow.addView(button("[mic]") { startVoiceInput() }, weight = 0f)
        sendButton = button("[run]") { onSend(inputField.text.toString()) }
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
        addBubble("system", "nyx agent online. type a command, ask a question, or build an app.")

        intent?.getStringExtra("nyx_cmd")?.let { onSend(it) }
    }

    override fun onResume() { super.onResume(); refreshDaemon(); checkPendingOAuth() }

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
        if (apiKey.isEmpty()) { addBubble("error", "no api key. paste your openrouter key above."); return }

        val model = (modelSpinner.selectedItem as? String) ?: DEFAULT_MODEL
        prefs.edit().putString("api_key", apiKey).putString("model", model).apply()

        addBubble("user", message)
        inputField.setText("")
        sendButton.isEnabled = false
        val thinking = addBubble("system", "…")

        ui.launch {
            val reply = runCatching { ensureAgent(apiKey, model).send(message) }
                .getOrElse { e -> "error: ${e.message}" }
            messagesView.removeView(thinking)
            addBubble("assistant", reply)
            speak(reply)
            sendButton.isEnabled = true
        }
    }

    private fun ensureAgent(apiKey: String, model: String): ConversationalAgent {
        if (agent == null || agentKey != apiKey || agentModel != model) {
            agent = ConversationalAgent(OpenRouterChatClient(apiKey), model, registry, mcpTools) { name, args, result ->
                onToolTrace(name, args, result)
            }
            agentKey = apiKey
            agentModel = model
        }
        return agent!!
    }

    /** If logged in, connects to the MCP server; otherwise opens the browser sign-in. */
    private fun connectMcp() {
        prefs.edit().putString("mcp_url", mcpUrlField.text.toString().trim().ifEmpty { DEFAULT_MCP_URL }).apply()
        if (oauth.isLoggedIn()) { connectMcpWithToken(); return }
        ui.launch {
            try {
                addBubble("system", "[mcp] opening sign-in… complete it in the browser, then return to Nyx")
                val clientId = oauth.ensureClientId()
                val verifier = oauth.newPkceVerifier()
                val authUrl = oauth.authorizeUrl(clientId, verifier, UUID.randomUUID().toString())
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
            } catch (e: Exception) {
                addBubble("error", "[mcp] couldn't start sign-in: ${e.message}")
            }
        }
    }

    /** Completes the OAuth code exchange after the browser redirect (survives process death). */
    private fun checkPendingOAuth() {
        prefs.getString("oauth_error", null)?.let {
            prefs.edit().remove("oauth_error").apply()
            addBubble("error", "[mcp] login: $it")
        }
        val code = prefs.getString("oauth_pending_code", null) ?: return
        prefs.edit().remove("oauth_pending_code").apply()
        ui.launch {
            try {
                addBubble("system", "[mcp] completing sign-in…")
                oauth.exchangeCode(code)
                addBubble("system", "[mcp] signed in ✓")
                connectMcpWithToken()
            } catch (e: Exception) {
                addBubble("error", "[mcp] token exchange failed: ${e.message}")
            }
        }
    }

    /** Discovers the MCP server's tools (with token refresh on failure) and adds them to the agent. */
    private fun connectMcpWithToken() {
        val url = mcpUrlField.text.toString().trim().ifEmpty { DEFAULT_MCP_URL }
        ui.launch {
            try {
                addBubble("system", "[mcp] connecting…")
                val tools = try {
                    RemoteMcpClient(url, oauth.accessToken()).connect()
                } catch (e: Exception) {
                    if (oauth.refresh()) RemoteMcpClient(url, oauth.accessToken()).connect() else throw e
                }
                val live = RemoteMcpClient(url, oauth.accessToken())
                mcpTools = tools.map { t ->
                    ExternalTool(t.name, t.description, t.inputSchema) { argsJson ->
                        live.callTool(t.name, parseJsonObj(argsJson))
                    }
                }
                agent = null  // rebuild so the agent advertises the new tools
                val names = tools.take(5).joinToString(", ") { it.name }
                addBubble("system", "[mcp] connected: ${tools.size} tools — $names${if (tools.size > 5) ", …" else ""}")
            } catch (e: Exception) {
                addBubble("error", "[mcp] ${e.message}")
            }
        }
    }

    private fun logoutMcp() {
        oauth.clearTokens()
        mcpTools = emptyList()
        agent = null
        addBubble("system", "[mcp] logged out, tools removed")
    }

    private fun parseJsonObj(s: String): JsonObject =
        runCatching { Json.parseToJsonElement(s).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    /** Renders a cyan [tool] line for each executed tool call (when verbose). */
    private fun onToolTrace(name: String, argsJson: String, result: String) {
        if (!verbose) return
        val args = argsJson.replace(Regex("\\s+"), " ").trim().take(120)
        runOnUiThread { addBubble("tool", "$name $args → ${result.take(160)}") }
    }

    private fun toggleTrace(btn: Button) {
        verbose = !verbose
        btn.text = if (verbose) "[trace:on]" else "[trace:off]"
    }

    private fun newChat() {
        agent?.reset()
        messagesView.removeAllViews()
        addBubble("system", "— new session —")
    }

    private fun buildRegistry(context: Context): SkillRegistry = SkillRegistry().apply {
        register(LaunchAppSkill(context))
        register(SystemControlsSkill(AndroidSystemControlsBridge(context)))
        register(SmsSkill(AndroidSmsBridge(context)))
        register(VoiceIOSkill(AndroidVoiceIOBridge(context)))
        register(BuildAppSkill(context) { spec -> generateAppHtml(spec) })
        register(OpenAppSkill(context))
        register(ListAppsSkill(context))
        register(PinAppSkill(context))
    }

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
        if (speakReplies && ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nyx-reply")
    }

    private fun toggleSpeak(btn: Button) {
        speakReplies = !speakReplies
        btn.text = if (speakReplies) "[tts:on]" else "[tts:off]"
        if (!speakReplies) tts?.stop()
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("speech recognition unavailable"); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { inputField.hint = "listening…" }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    inputField.hint = "type a command…"
                    if (text.isNotBlank()) onSend(text)
                }
                override fun onError(error: Int) {
                    inputField.hint = "type a command…"
                    toast("voice error ($error)")
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

    /** Adds a terminal line and returns it (so a placeholder can be replaced/removed). */
    private fun addBubble(role: String, text: String): View {
        val (prefix, color) = when (role) {
            "user" -> "> " to cUser
            "assistant" -> "" to cFg
            "error" -> "! " to cErr
            "tool" -> "[tool] " to cTool
            else -> "" to cDim   // system
        }
        val line = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13.5f
            setTextColor(color)
            setPadding(0, dp(3), 0, dp(3))
            this.text = prefix + text
            setTextIsSelectable(true)
        }
        messagesView.addView(line, fullWidth())
        conversation.post { conversation.fullScroll(View.FOCUS_DOWN) }
        return line
    }

    private fun refreshDaemon() {
        val running = DaemonLifecycleManager.isRunning(this)
        daemonStatus.text = if (running) "● daemon online" else "○ daemon offline"
        daemonStatus.setTextColor(if (running) cDim else Color.parseColor("#7A4A2A"))
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** A plain monospace line/label. */
    private fun term(text: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    /** A terminal-styled, underline-free input field. */
    private fun termField(prefill: String, hint: String, password: Boolean) = EditText(this).apply {
        layoutParams = fullWidth()
        setText(prefill)
        this.hint = hint
        textSize = 13.5f
        typeface = Typeface.MONOSPACE
        setTextColor(cUser)
        setHintTextColor(cDim)
        background = null
        setPadding(0, dp(6), 0, dp(6))
        inputType = if (password) {
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            android.text.InputType.TYPE_CLASS_TEXT
        }
    }

    /** A dark button with a thin green border and green monospace label. */
    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(cFg)
        typeface = Typeface.MONOSPACE
        textSize = 12f
        isAllCaps = false
        stateListAnimator = null
        setPadding(dp(8), dp(4), dp(8), dp(4))
        minWidth = 0
        minimumWidth = 0
        background = GradientDrawable().apply {
            setColor(cBg)
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), cDim)
        }
        setOnClickListener { onClick(it) }
    }

    /** ArrayAdapter that renders the model list green-on-dark, monospace. */
    private fun terminalSpinnerAdapter() = object : ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_item, OPENROUTER_MODELS
    ) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getView(position, convertView, parent) as TextView, dropdown = false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            style(super.getDropDownView(position, convertView, parent) as TextView, dropdown = true)
        private fun style(tv: TextView, dropdown: Boolean): TextView = tv.apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(cFg)
            if (dropdown) setBackgroundColor(cBg)
        }
    }

    private fun LinearLayout.addView(view: View, weight: Float) {
        val width = if (weight > 0f) 0 else WRAP_CONTENT
        addView(view, LinearLayout.LayoutParams(width, WRAP_CONTENT, weight))
    }

    private fun fullWidth() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_MODEL = "openai/gpt-4o-mini"
        const val DEFAULT_MCP_URL = "https://mcp.higgsfield.ai/mcp"

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
