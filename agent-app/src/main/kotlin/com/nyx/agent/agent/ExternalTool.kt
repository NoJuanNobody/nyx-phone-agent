package com.nyx.agent.agent

import kotlinx.serialization.json.JsonObject

/**
 * A tool supplied to [ConversationalAgent] from outside the local [com.nyx.agent.skill.SkillRegistry]
 * — e.g. tools discovered from a remote MCP server. The agent advertises it to the LLM using
 * [parameters] (a JSON-Schema object) and routes matching tool calls to [invoke].
 *
 * @param invoke receives the raw JSON arguments string from the model and returns a result string.
 */
class ExternalTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val invoke: suspend (argsJson: String) -> String,
)
