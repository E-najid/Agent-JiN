package com.ngi.agentjin.core.plugin

/**
 * ServiceLoader entry point. To add a plugin in a later phase:
 *  1. Create a class implementing [Plugin]
 *  2. Create a no-arg [PluginFactory] that constructs it from [PluginDependencies]
 *  3. Add the factory's FQCN to META-INF/services/com.ngi.agentjin.core.plugin.PluginFactory
 *
 * No core files need to change.
 */
fun interface PluginFactory {
    fun create(deps: PluginDependencies): Plugin
}
