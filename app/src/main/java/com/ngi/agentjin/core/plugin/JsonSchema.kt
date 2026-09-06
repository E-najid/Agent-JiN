package com.ngi.agentjin.core.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonSchema(
    val type: String = "object",
    val properties: Map<String, JsonSchemaProperty> = emptyMap(),
    val required: List<String> = emptyList(),
    val description: String? = null,
)

@Serializable
data class JsonSchemaProperty(
    val type: String,
    val description: String? = null,
    @SerialName("enum")
    val enumValues: List<String>? = null,
    val items: JsonSchemaProperty? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val default: String? = null,
)

private val schemaJson = Json { encodeDefaults = true; explicitNulls = false }

fun JsonSchema.toJsonObject(): JsonObject {
    return schemaJson.encodeToJsonElement(JsonSchema.serializer(), this) as JsonObject
}
