package com.nyx.agent.planner

import com.nyx.agent.orchestration.OrchestrationPlan
import com.nyx.agent.orchestration.PlanParser
import com.nyx.agent.skill.SkillRegistry
import com.nyx.llm.LlmInferenceEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Turns a natural-language goal into an [OrchestrationPlan] by asking an
 * [LlmInferenceEngine] to emit a JSON plan over the registered skills.
 *
 * The skill catalog (names + descriptions) is injected into the prompt so the model
 * only ever references skills that actually exist; [PlanParser] then converts the
 * model's step list into typed [com.nyx.agent.orchestration.PlanStep]s for
 * [com.nyx.agent.orchestration.PlanExecutor].
 */
class Planner(
    private val engine: LlmInferenceEngine,
    private val registry: SkillRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The raw LLM response from the most recent [plan] call (for debugging/UI). */
    @Volatile
    var lastRawResponse: String = ""
        private set

    suspend fun plan(goal: String): OrchestrationPlan {
        val response = engine.generate(buildPrompt(goal), maxTokens = 800)
        lastRawResponse = response
        val steps = parseSteps(response)
        return PlanParser.parse(goal, steps)
    }

    private fun buildPrompt(goal: String): String {
        val catalog = registry.all()
            .sortedBy { it.name }
            .joinToString("\n") { "- ${it.name}: ${it.description}" }
        return """
            You are Nyx, an on-device phone agent. Convert the user's request into a JSON plan
            that calls the available skills below. Respond with ONLY a JSON object — no prose,
            no markdown fences.

            Available skills:
            $catalog

            Argument hints per skill:
            - launch_app: {"app_name": "<fuzzy app label>"} OR {"package_name": "<pkg>"} OR {"deep_link": "<uri>"}
            - system_controls: {"control": "wifi|bluetooth|volume|brightness", "action": "get|set|enable|disable", "enabled": <bool>, "level": <int 0-100>, "stream": "media|ring|alarm"}
            - sms: {"action": "read|send", "to": "<number>", "body": "<text>", "limit": <int>}
            - voice_io: {"action": "speak|listen", "text": "<text to speak>"}

            Required output shape:
            {"goal": "<restate the goal>", "steps": [
              {"step": 1, "skill": "<one of the skill names above>", "args": { ... }, "description": "<short>", "destructive": false}
            ]}

            Rules:
            - Only use skill names from the list above.
            - Mark a step "destructive": true if it sends messages or changes device state irreversibly.
            - If no available skill can satisfy the request, return {"goal": "<goal>", "steps": []}.

            User request: $goal
        """.trimIndent()
    }

    private fun parseSteps(raw: String): List<Map<String, Any>> {
        val obj = json.parseToJsonElement(extractJsonObject(raw)).jsonObject
        val steps = obj["steps"] as? JsonArray ?: return emptyList()
        return steps.mapNotNull { element ->
            val step = element as? JsonObject ?: return@mapNotNull null
            buildMap {
                (step["step"] as? JsonPrimitive)?.intOrNull?.let { put("step", it) }
                (step["skill"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { put("skill", it) }
                (step["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { put("description", it) }
                (step["destructive"] as? JsonPrimitive)?.booleanOrNull?.let { put("destructive", it) }
                (step["args"] as? JsonObject)?.let { put("args", jsonObjectToMap(it)) }
            }
        }
    }

    private fun jsonObjectToMap(obj: JsonObject): Map<String, Any> =
        obj.mapValues { (_, value) -> jsonToAny(value) }

    private fun jsonToAny(element: JsonElement): Any = when (element) {
        is JsonObject -> jsonObjectToMap(element)
        is JsonArray -> element.map { jsonToAny(it) }
        is JsonPrimitive ->
            if (element.isString) element.content
            else element.booleanOrNull
                ?: element.intOrNull
                ?: element.longOrNull
                ?: element.doubleOrNull
                ?: element.content
    }

    /** Tolerate models that wrap JSON in prose or ```json fences by slicing to the outer braces. */
    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }
}
