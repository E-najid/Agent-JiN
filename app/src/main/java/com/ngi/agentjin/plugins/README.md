# Plugins

Phase 1 ships three plugins:

- `screen_agent`
- `app_manager`
- `settings`

To add a plugin in a later phase (voice, terminal, connectors, messaging, payments, …):

1. New Kotlin file under this package tree implementing `com.ngi.agentjin.core.plugin.Plugin`.
2. A public no-arg `PluginFactory` that receives `PluginDependencies`.
3. One line in  
   `app/src/main/resources/META-INF/services/com.ngi.agentjin.core.plugin.PluginFactory`

Do **not** edit `PluginManager`’s phase-1 bootstrap list. Do **not** put fake/stub tools in the plugins UI.

Disabled plugins are omitted from the active map, so `loadResources()` never runs.
