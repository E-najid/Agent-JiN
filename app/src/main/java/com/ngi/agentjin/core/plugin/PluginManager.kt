package com.ngi.agentjin.core.plugin

import com.ngi.agentjin.core.safety.ConfirmationGate
import com.ngi.agentjin.core.safety.PermissionGuard
import com.ngi.agentjin.core.safety.UndoManager
import com.ngi.agentjin.core.storage.EncryptedMemoryStore
import kotlinx.serialization.json.JsonObject
import java.util.ServiceLoader

/**
 * Registers plugins discovered via [ServiceLoader] and exposes their schemas
 * as tools to the text model. Disabled plugins are not instantiated, so their
 * models/resources are never loaded.
 */
class PluginManager(
    private val deps: PluginDependencies,
    private val memory: EncryptedMemoryStore,
    private val permissionGuard: PermissionGuard,
    private val confirmationGate: ConfirmationGate,
    private val undoManager: UndoManager,
) {
    private val factories: List<PluginFactory> by lazy { loadFactories() }

    private fun loadFactories(): List<PluginFactory> {
        val loaded = LinkedHashMap<String, PluginFactory>()
        ServiceLoader.load(PluginFactory::class.java, PluginFactory::class.java.classLoader)
            .forEach { loaded[it.javaClass.name] = it }
        // Phase-1 bootstrap so the three plugins load even if META-INF/services is stripped.
        // Later plugins still register via ServiceLoader only — do not add them here.
        val phase1 = listOf(
            "com.ngi.agentjin.plugins.screen.ScreenAgentPluginFactory",
            "com.ngi.agentjin.plugins.apps.AppManagerPluginFactory",
            "com.ngi.agentjin.plugins.settings.SettingsPluginFactory",
        )
        for (c in phase1) {
            if (c !in loaded) {
                runCatching {
                    loaded[c] = Class.forName(c).getDeclaredConstructor().newInstance() as PluginFactory
                }
            }
        }
        return loaded.values.toList()
    }

    private var active: LinkedHashMap<String, Plugin> = LinkedHashMap()

    val plugins: List<Plugin> get() = active.values.toList()

    fun catalog(): List<Plugin> = factories.map { it.create(deps) }

    fun availableNames(): List<String> = catalog().map { it.name }

    suspend fun reload() {
        active.values.forEach { runCatching { it.unloadResources() } }
        active.clear()
        val enabled = memory.readEnabledPluginList()?.toSet()
            ?: EncryptedMemoryStore.DEFAULT_ENABLED
        for (factory in factories) {
            val plugin = factory.create(deps)
            if (plugin.name in enabled) {
                active[plugin.name] = plugin
            }
        }
    }

    fun toolSchemasForModel(): String {
        return plugins.joinToString("\n\n") { p ->
            buildString {
                appendLine("tool: ${p.name}")
                appendLine("description: ${p.description}")
                appendLine("sensitive: ${p.isSensitive}")
                append("parameters: ")
                append(p.parameters.toJsonObject())
            }
        }
    }

    fun get(name: String): Plugin? = active[name]

    fun isEnabled(name: String): Boolean = active.containsKey(name)

    suspend fun setEnabled(name: String, enabled: Boolean) {
        val current = (memory.readEnabledPluginList() ?: EncryptedMemoryStore.DEFAULT_ENABLED.toList()).toMutableList()
        if (enabled && name !in current) current += name
        if (!enabled) current.remove(name)
        memory.writeEnabledPlugins(current)
        if (!enabled) {
            active.remove(name)?.unloadResources()
        } else if (!active.containsKey(name)) {
            val plugin = factories.firstOrNull { it.create(deps).name == name }?.create(deps)
            if (plugin != null) active[plugin.name] = plugin
        }
    }

    suspend fun execute(name: String, params: JsonObject, taskId: String?): PluginResult {
        val plugin = active[name]
            ?: return PluginResult.fail("Plugin '$name' is disabled or not installed")
        if (plugin.isSensitive) {
            val ok = confirmationGate.request(
                title = "Allow ${plugin.name}?",
                message = plugin.description + "\n\n" + params.toString(),
            )
            if (!ok) return PluginResult.fail("User declined the action")
        }
        if (plugin.requiresLazyResources) {
            plugin.loadResources()
        }
        val result = permissionGuard.around(plugin, params, taskId) {
            plugin.execute(params)
        }
        if (result.ok && result.canUndo && result.undoToken != null) {
            undoManager.push(plugin.name, result.undoToken, params)
        }
        return result
    }

    suspend fun undoLast(): PluginResult {
        val rec = undoManager.pop() ?: return PluginResult.fail("Nothing to undo")
        val plugin = active[rec.pluginName]
            ?: return PluginResult.fail("Plugin ${rec.pluginName} is no longer enabled")
        return permissionGuard.around(plugin, rec.params, taskId = null) {
            plugin.undo(rec.token)
        }
    }
}
