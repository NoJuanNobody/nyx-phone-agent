package com.nyx.agent.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates LLM-generated tool-call arguments against a tool's [ToolDescriptor]
 * parameter schema before execution.
 *
 * On failure, returns a structured error string suitable for injection back
 * into the LLM context so the model can retry with corrected arguments.
 */
object JsonSchemaValidator {

    /**
     * Validate [arguments] against [descriptor]. Returns null on success or a
     * human-readable error string describing the first violation.
     */
    fun validate(descriptor: ToolDescriptor, arguments: JsonObject): String? {
        for (param in descriptor.params) {
            val present = arguments[param.name]
            if (param.required && present == null) {
                return "Missing required argument '${param.name}' for tool '${descriptor.name}'"
            }
            if (present != null) {
                val typeErr = checkType(param.name, param.type, present)
                if (typeErr != null) return typeErr
                if (param.`enum` != null && present is JsonPrimitive) {
                    val v = present.contentOrNull
                    if (v == null || v !in param.`enum`) {
                        return "Argument '${param.name}' must be one of ${param.`enum`}; got '$v'"
                    }
                }
            }
        }
        return null
    }

    private fun checkType(name: String, expected: String, value: JsonElement): String? {
        val actual = when (value) {
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.contentOrNull?.let { it.toIntOrNull() != null || it.toDoubleOrNull() != null } == true -> "number"
                value.contentOrNull == "true" || value.contentOrNull == "false" -> "boolean"
                else -> "string"
            }
            is JsonObject -> "object"
            is JsonArray -> "array"
            else -> "unknown"
        }
        return if (actual == expected) null
        else "Argument '$name' must be of type '$expected'; got '$actual'"
    }
}
