package com.ngi.agentjin.core.safety

import com.ngi.agentjin.core.planning.TaskHistoryStore
import com.ngi.agentjin.core.plugin.Plugin
import com.ngi.agentjin.core.plugin.PluginResult
import kotlinx.serialization.json.JsonObject

/**
 * Core (not a plugin). Every plugin call is logged automatically, even when
 * few plugins exist yet.
 */
class PermissionGuard(
    private val history: TaskHistoryStore,
) {
    suspend fun around(
        plugin: Plugin,
        params: JsonObject,
        taskId: String?,
        block: suspend () -> PluginResult,
    ): PluginResult {
        val id = history.logPluginCallStart(
            plugin = plugin.name,
            params = params,
            sensitive = plugin.isSensitive,
            taskId = taskId,
        )
        return try {
            val result = block()
            history.logPluginCallEnd(id, result)
            result
        } catch (t: Throwable) {
            val fail = PluginResult.fail(t.message ?: t.javaClass.simpleName)
            history.logPluginCallEnd(id, fail)
            fail
        }
    }
}
