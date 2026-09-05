package com.ngi.agentjin.plugins.apps

import android.content.Intent
import android.content.pm.PackageManager
import com.ngi.agentjin.core.plugin.JsonSchema
import com.ngi.agentjin.core.plugin.JsonSchemaProperty
import com.ngi.agentjin.core.plugin.Plugin
import com.ngi.agentjin.core.plugin.PluginDependencies
import com.ngi.agentjin.core.plugin.PluginFactory
import com.ngi.agentjin.core.plugin.PluginResult
import com.ngi.agentjin.core.screen.JinAccessibilityService
import com.ngi.agentjin.plugins.screen.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import android.accessibilityservice.AccessibilityService

class AppManagerPlugin(
    private val deps: PluginDependencies,
) : Plugin {
    override val name = "app_manager"
    override val description = "List installed launchable apps, open an app by name or package, or send the current app to the background."
    override val isSensitive: Boolean get() = false
    override val parameters = JsonSchema(
        properties = mapOf(
            "action" to JsonSchemaProperty(
                type = "string",
                enum = listOf("list", "open", "close"),
                description = "list | open | close",
            ),
            "package_name" to JsonSchemaProperty("string", "Application id, e.g. com.android.settings"),
            "app_name" to JsonSchemaProperty("string", "Visible launcher label, matched case-insensitively"),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val action = params.str("action") ?: return PluginResult.fail("missing action")
        return when (action) {
            "list" -> listApps()
            "open" -> openApp(params.str("package_name"), params.str("app_name"))
            "close" -> closeApp()
            else -> PluginResult.unimplemented("app_manager action '$action'")
        }
    }

    private fun listApps(): PluginResult {
        val pm = deps.appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val apps = found.map { ri ->
            val label = ri.loadLabel(pm).toString()
            val pkg = ri.activityInfo.packageName
            buildJsonObject {
                put("name", JsonPrimitive(label))
                put("package", JsonPrimitive(pkg))
            }
        }.sortedBy { it["name"]?.toString()?.lowercase() }
        return PluginResult.ok(
            "Found ${apps.size} launchable apps",
            buildJsonObject { put("apps", JsonArray(apps)) },
        )
    }

    private fun openApp(packageName: String?, appName: String?): PluginResult {
        val pm = deps.appContext.packageManager
        val pkg = when {
            !packageName.isNullOrBlank() -> packageName
            !appName.isNullOrBlank() -> resolvePackageByLabel(pm, appName)
                ?: return PluginResult.fail("No launchable app named '$appName'")
            else -> return PluginResult.fail("Provide package_name or app_name")
        }
        val launch = pm.getLaunchIntentForPackage(pkg)
            ?: return PluginResult.fail("Package $pkg has no launcher activity")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            deps.appContext.startActivity(launch)
            PluginResult.ok("Opened $pkg", canUndo = true, undoToken = "home")
        } catch (t: Throwable) {
            PluginResult.fail("Unable to open $pkg: ${t.message}")
        }
    }

    private fun closeApp(): PluginResult {
        val svc = JinAccessibilityService.instance
            ?: return PluginResult.fail(
                "Closing another app requires the accessibility service (Home / Recents). Android does not allow third-party apps to force-stop other processes.",
            )
        val ok = svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
        return if (ok) {
            PluginResult.ok("Sent HOME. The previous app is in the background; force-stop is not available without privileged permissions.")
        } else {
            PluginResult.fail("HOME action failed")
        }
    }

    override suspend fun undo(token: String): PluginResult {
        if (token != "home") return PluginResult.fail("unknown undo token")
        return closeApp()
    }

    private fun resolvePackageByLabel(pm: PackageManager, name: String): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val q = name.trim()
        val exact = found.firstOrNull { it.loadLabel(pm).toString().equals(q, ignoreCase = true) }
        if (exact != null) return exact.activityInfo.packageName
        val contains = found.filter { it.loadLabel(pm).toString().contains(q, ignoreCase = true) }
        return contains.singleOrNull()?.activityInfo.packageName
    }
}

class AppManagerPluginFactory : PluginFactory {
    override fun create(deps: PluginDependencies): Plugin = AppManagerPlugin(deps)
}
