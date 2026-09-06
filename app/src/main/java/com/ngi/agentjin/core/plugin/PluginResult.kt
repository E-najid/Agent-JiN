package com.ngi.agentjin.core.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class PluginResult(
    val ok: Boolean,
    val message: String,
    val data: JsonObject = JsonObject(emptyMap()),
    val canUndo: Boolean = false,
    val undoToken: String? = null,
    val needsUserInput: Boolean = false,
    val userPrompt: String? = null,
) {
    companion object {
        fun ok(message: String, data: JsonObject = JsonObject(emptyMap()), canUndo: Boolean = false, undoToken: String? = null) =
            PluginResult(true, message, data, canUndo, undoToken)

        fun fail(message: String, data: JsonObject = JsonObject(emptyMap())) =
            PluginResult(false, message, data)

        fun askUser(question: String) =
            PluginResult(ok = false, message = question, needsUserInput = true, userPrompt = question)

        fun unimplemented(what: String) =
            PluginResult(false, "not implemented: $what")
    }
}

fun jsonString(key: String, value: String) = buildJsonObject { put(key, JsonPrimitive(value)) }
