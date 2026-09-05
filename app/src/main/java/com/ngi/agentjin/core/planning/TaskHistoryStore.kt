package com.ngi.agentjin.core.planning

import com.ngi.agentjin.core.plugin.PluginResult
import com.ngi.agentjin.core.storage.StorageRoot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class HistoryEvent(
    val id: String,
    val at: Long,
    val kind: String,
    val taskId: String? = null,
    val plugin: String? = null,
    val sensitive: Boolean = false,
    val params: JsonObject? = null,
    val ok: Boolean? = null,
    val message: String? = null,
)

class TaskHistoryStore(
    private val storage: StorageRoot,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val open = ConcurrentHashMap<String, HistoryEvent>()

    fun newTaskId(): String = UUID.randomUUID().toString()

    fun logPluginCallStart(plugin: String, params: JsonObject, sensitive: Boolean, taskId: String?): String {
        val ev = HistoryEvent(
            id = UUID.randomUUID().toString(),
            at = System.currentTimeMillis(),
            kind = "plugin_start",
            taskId = taskId,
            plugin = plugin,
            sensitive = sensitive,
            params = params,
        )
        open[ev.id] = ev
        append(ev, taskId)
        return ev.id
    }

    fun logPluginCallEnd(id: String, result: PluginResult) {
        val start = open.remove(id)
        val ev = HistoryEvent(
            id = UUID.randomUUID().toString(),
            at = System.currentTimeMillis(),
            kind = "plugin_end",
            taskId = start?.taskId,
            plugin = start?.plugin,
            sensitive = start?.sensitive ?: false,
            ok = result.ok,
            message = result.message,
        )
        append(ev, start?.taskId)
    }

    fun logTask(taskId: String, kind: String, message: String) {
        append(
            HistoryEvent(
                id = UUID.randomUUID().toString(),
                at = System.currentTimeMillis(),
                kind = kind,
                taskId = taskId,
                message = message,
            ),
            taskId,
        )
    }

    fun listTaskFiles(): List<String> {
        return storage.list(listOf("task_history")).map { it.name ?: "" }.filter { it.endsWith(".jsonl") }
    }

    fun readTask(taskId: String): List<HistoryEvent> {
        val bytes = storage.readBytes(listOf("task_history", "$taskId.jsonl")) ?: return emptyList()
        return bytes.toString(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<HistoryEvent>(it) }.getOrNull() }
            .toList()
    }

    private fun append(ev: HistoryEvent, taskId: String?) {
        val name = (taskId ?: "untasked") + ".jsonl"
        val path = listOf("task_history", name)
        val existing = storage.readBytes(path)?.toString(Charsets.UTF_8).orEmpty()
        val line = json.encodeToString(ev)
        storage.writeBytes(path, (existing + line + "\n").toByteArray())
    }
}
