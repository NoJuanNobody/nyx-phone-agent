package com.nyx.agent.agent

import com.nyx.agent.skill.SkillRegistry
import com.nyx.agent.skill.SkillResult
import com.nyx.llm.chat.ChatMessage
import com.nyx.llm.chat.OpenRouterChatClient
import com.nyx.llm.chat.ToolCall
import com.nyx.llm.chat.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * A conversational, tool-calling agent. The user talks to it in natural language; it replies
 * in natural language and calls skills (as tools) when an action is wanted, then narrates the
 * outcome. Maintains conversation [history] across turns so follow-ups work.
 *
 * Uses [OpenRouterChatClient] for now (OpenAI-compatible tool calling); the same loop runs
 * against a local model server later by swapping the client.
 */
class ConversationalAgent(
    private val client: OpenRouterChatClient,
    private val model: String,
    private val registry: SkillRegistry,
    /** Tools from outside the local registry (e.g. remote MCP servers). */
    private val externalTools: List<ExternalTool> = emptyList(),
    /** Invoked after each tool runs, with its name, raw JSON args, and result string. */
    private val onTool: (name: String, argsJson: String, result: String) -> Unit = { _, _, _ -> },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val history = mutableListOf(ChatMessage("system", SYSTEM_PROMPT))
    private val externalByName = externalTools.associateBy { it.name }

    /** Send a user message; returns Nyx's natural-language reply (after running any tools). */
    suspend fun send(userMessage: String): String {
        history.add(ChatMessage("user", userMessage))
        var iterations = 0
        while (iterations++ < MAX_TOOL_ITERATIONS) {
            val result = client.chat(model, history, toolSpecs())
            if (result.toolCalls.isEmpty()) {
                val reply = result.content?.trim().orEmpty().ifEmpty { "Okay." }
                history.add(ChatMessage("assistant", reply))
                return reply
            }
            // Assistant decided to call tools — record that turn, run them, feed results back.
            history.add(ChatMessage("assistant", result.content, toolCalls = result.toolCalls))
            for (call in result.toolCalls) {
                val outcome = runSkill(call)
                android.util.Log.i("NyxAgent", "tool ${call.name} ${call.argumentsJson} -> $outcome")
                onTool(call.name, call.argumentsJson, outcome)
                history.add(ChatMessage("tool", outcome, toolCallId = call.id, name = call.name))
            }
        }
        val giveUp = "Sorry — I couldn't complete that in a reasonable number of steps."
        history.add(ChatMessage("assistant", giveUp))
        return giveUp
    }

    /** Start a fresh conversation. */
    fun reset() {
        history.clear()
        history.add(ChatMessage("system", SYSTEM_PROMPT))
    }

    private suspend fun runSkill(call: ToolCall): String {
        registry.find(call.name)?.let { skill ->
            return when (val r = skill.execute(parseArgs(call.argumentsJson))) {
                is SkillResult.Success -> if (r.output.isEmpty()) "ok" else "ok: ${r.output}"
                is SkillResult.Failure -> "failed: ${r.error}"
                is SkillResult.SkillNotFound -> "error: skill not found"
                is SkillResult.ConfirmationDenied -> "blocked: needs confirmation"
                is SkillResult.PermissionDenied -> "blocked: missing permissions ${r.missingPermissions}"
            }
        }
        externalByName[call.name]?.let { tool ->
            return runCatching { tool.invoke(call.argumentsJson) }.getOrElse { "failed: ${it.message}" }
        }
        return "error: no such tool '${call.name}'"
    }

    private fun parseArgs(argsJson: String): Map<String, Any> {
        val obj = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapValues { (_, value) ->
            val p = value as? JsonPrimitive ?: return@mapValues value.toString()
            if (p.isString) p.content
            else p.booleanOrNull ?: p.intOrNull ?: p.longOrNull ?: p.doubleOrNull ?: p.content
        }
    }

    private fun toolSpecs(): List<ToolSpec> {
        val skillSpecs = registry.all().sortedBy { it.name }.map { skill ->
            val schema = TOOL_SCHEMAS[skill.name] ?: PERMISSIVE_SCHEMA
            ToolSpec(skill.name, skill.description, json.parseToJsonElement(schema).jsonObject)
        }
        val extSpecs = externalTools.map { ToolSpec(it.name, it.description, it.parameters) }
        return skillSpecs + extSpecs
    }

    private companion object {
        const val MAX_TOOL_ITERATIONS = 6

        val SYSTEM_PROMPT = """
            You are Nyx, a friendly, concise assistant living on the user's phone. You can control
            the phone by calling the provided tools (launch apps, system controls, SMS, and more).
            You can also BUILD small apps on demand: when the user asks for a tool, calculator,
            tracker, game, or similar, call build_app with a clear name and a detailed spec — it
            generates and runs the app on the phone immediately. AFTER you build an app, always
            proactively offer to add it to the home screen — e.g. end your reply with "Want me to
            add it to your home screen?" If the user says yes, call pin_app (Android shows a one-time
            "Add to Home screen" confirmation). If they ask up front to put it on the home screen,
            pass pin=true to build_app instead. Converse naturally: greet, answer
            questions about what you can do, and only call a tool when the user actually wants an
            action. After acting, tell the user in plain language what you did — and if a tool
            failed, say so briefly and why. Keep replies short and natural; they may be spoken aloud.
        """.trimIndent()

        const val PERMISSIVE_SCHEMA = """{"type":"object","additionalProperties":true}"""

        val TOOL_SCHEMAS = mapOf(
            "launch_app" to """
                {"type":"object","properties":{
                  "app_name":{"type":"string","description":"Fuzzy app label, e.g. Chrome"},
                  "package_name":{"type":"string"},
                  "deep_link":{"type":"string","description":"A URI to open"}
                }}""",
            "system_controls" to """
                {"type":"object","properties":{
                  "control":{"type":"string","enum":["wifi","bluetooth","volume","brightness"]},
                  "action":{"type":"string","enum":["get","set","enable","disable"]},
                  "enabled":{"type":"boolean"},
                  "level":{"type":"integer","description":"0-100"},
                  "stream":{"type":"string","enum":["media","ring","alarm"]}
                },"required":["control"]}""",
            "sms" to """
                {"type":"object","properties":{
                  "action":{"type":"string","enum":["read","send"]},
                  "to":{"type":"string"},
                  "body":{"type":"string"},
                  "limit":{"type":"integer"}
                },"required":["action"]}""",
            "voice_io" to """
                {"type":"object","properties":{
                  "action":{"type":"string","enum":["speak","listen"]},
                  "text":{"type":"string"}
                },"required":["action"]}""",
            "build_app" to """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"Short app name"},
                  "spec":{"type":"string","description":"What the app should do and look like"},
                  "pin":{"type":"boolean","description":"true to also add the app to the home screen"}
                },"required":["name","spec"]}""",
            "open_app" to """
                {"type":"object","properties":{
                  "name":{"type":"string"}
                },"required":["name"]}""",
            "list_apps" to """{"type":"object","properties":{}}""",
            "pin_app" to """
                {"type":"object","properties":{
                  "name":{"type":"string"}
                },"required":["name"]}""",
        )
    }
}
