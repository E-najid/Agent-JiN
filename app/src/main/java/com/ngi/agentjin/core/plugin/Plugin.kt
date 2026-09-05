package com.ngi.agentjin.core.plugin

import kotlinx.serialization.json.JsonObject

/**
 * Common plugin interface. Future capabilities (voice, terminal, connectors,
 * messaging, payments, …) are added by implementing this interface and
 * registering a [PluginFactory] — the core does not need to change.
 */
interface Plugin {
    val name: String
    val description: String
    val parameters: JsonSchema

    /** Destructive, financial, or irreversible actions must return true. */
    val isSensitive: Boolean get() = false

    /**
     * When true, [loadResources] / [unloadResources] own heavy assets (models).
     * A disabled plugin is never constructed into the active set, so those
     * resources are never loaded.
     */
    val requiresLazyResources: Boolean get() = false

    suspend fun loadResources() {}
    suspend fun unloadResources() {}

    suspend fun execute(params: JsonObject): PluginResult

    suspend fun undo(token: String): PluginResult =
        PluginResult.fail("Undo is not implemented for $name")
}
