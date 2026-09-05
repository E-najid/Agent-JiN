package com.ngi.agentjin.plugins.screen

import android.accessibilityservice.AccessibilityService
import com.ngi.agentjin.core.plugin.JsonSchema
import com.ngi.agentjin.core.plugin.JsonSchemaProperty
import com.ngi.agentjin.core.plugin.Plugin
import com.ngi.agentjin.core.plugin.PluginDependencies
import com.ngi.agentjin.core.plugin.PluginFactory
import com.ngi.agentjin.core.plugin.PluginResult
import com.ngi.agentjin.core.screen.JinAccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ScreenAgentPlugin(
    private val deps: PluginDependencies,
) : Plugin {
    override val name = "screen_agent"
    override val description =
        "Read the current screen via the accessibility tree and perform taps, typing, scrolls, and system navigation. Falls back to the vision model + screenshot when the tree is insufficient."
    override val isSensitive: Boolean
        get() = deps.devicePreferences.confirmScreenActions
    override val requiresLazyResources = true
    override val parameters = JsonSchema(
        properties = mapOf(
            "action" to JsonSchemaProperty(
                type = "string",
                description = "dump_tree | click | long_click | type | scroll | back | home | recents | tap | describe",
                enum = listOf("dump_tree", "click", "long_click", "type", "scroll", "back", "home", "recents", "tap", "describe"),
            ),
            "text" to JsonSchemaProperty("string", "Visible text, content-desc substring, or text to type"),
            "view_id" to JsonSchemaProperty("string", "Fully qualified resource id"),
            "x" to JsonSchemaProperty("integer", "Screen X in pixels for tap"),
            "y" to JsonSchemaProperty("integer", "Screen Y in pixels for tap"),
            "direction" to JsonSchemaProperty("string", "scroll direction: forward or backward", enum = listOf("forward", "backward")),
            "question" to JsonSchemaProperty("string", "Question for the vision model when action=describe"),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val action = params.str("action") ?: return PluginResult.fail("missing action")
        val svc = JinAccessibilityService.instance
        if (svc == null && action != "describe") {
            return PluginResult.fail("Accessibility service is not enabled. Open Android Settings → Accessibility → Agent JiN screen agent and turn it on.")
        }
        return when (action) {
            "dump_tree" -> {
                val dump = deps.screen.dumpText()
                PluginResult.ok("Current UI tree", buildJsonObject { put("tree", JsonPrimitive(dump)) })
            }
            "click" -> {
                val node = resolve(svc!!, params) ?: return PluginResult.fail("No matching UI node")
                if (svc.clickNode(node)) PluginResult.ok("Clicked") else PluginResult.fail("Click failed")
            }
            "long_click" -> {
                val node = resolve(svc!!, params) ?: return PluginResult.fail("No matching UI node")
                if (svc.longClickNode(node)) PluginResult.ok("Long-clicked") else PluginResult.fail("Long-click failed")
            }
            "type" -> {
                val text = params.str("text") ?: return PluginResult.fail("missing text")
                val node = resolve(svc!!, params) ?: svc.rootInActiveWindow
                    ?: return PluginResult.fail("No focused/editable field")
                if (svc.typeInto(node, text)) PluginResult.ok("Typed text") else PluginResult.fail("TYPE action failed")
            }
            "scroll" -> {
                val dir = params.str("direction") ?: "forward"
                if (svc!!.scroll(dir)) PluginResult.ok("Scrolled $dir") else PluginResult.fail("No scrollable node")
            }
            "back" -> okGlobal(svc!!, AccessibilityService.GLOBAL_ACTION_BACK, "Back")
            "home" -> okGlobal(svc!!, AccessibilityService.GLOBAL_ACTION_HOME, "Home")
            "recents" -> okGlobal(svc!!, AccessibilityService.GLOBAL_ACTION_RECENTS, "Recents")
            "tap" -> {
                val x = params.int("x") ?: return PluginResult.fail("missing x")
                val y = params.int("y") ?: return PluginResult.fail("missing y")
                if (svc!!.tap(x.toFloat(), y.toFloat())) PluginResult.ok("Tapped ($x,$y)") else PluginResult.fail("Gesture failed")
            }
            "describe" -> describe(params.str("question") ?: "Describe the screen and list tappable elements with approximate positions.")
            else -> PluginResult.unimplemented("screen_agent action '$action'")
        }
    }

    private suspend fun describe(question: String): PluginResult {
        val dump = deps.screen.dumpText()
        if (!deps.screen.treeInsufficient(dump)) {
            return PluginResult.ok("Accessibility tree is sufficient; vision model not used.", buildJsonObject {
                put("tree", JsonPrimitive(dump))
            })
        }
        val shot = deps.screenshots.captureRgb()
            ?: return PluginResult.fail("Screenshot capture is not available. Grant screen-capture permission when prompted, or enable the accessibility service (API 30+ can screenshot without MediaProjection).")
        val (rgb, w, h) = shot
        val answer = try {
            deps.vision.describeScreen(rgb, w, h, question)
        } catch (t: Throwable) {
            return PluginResult.fail(t.message ?: "vision model failed")
        }
        if (answer.startsWith("ERROR:")) return PluginResult.fail(answer)
        return PluginResult.ok(answer, buildJsonObject {
            put("width", JsonPrimitive(w))
            put("height", JsonPrimitive(h))
        })
    }

    private fun resolve(svc: JinAccessibilityService, params: JsonObject) = when {
        !params.str("view_id").isNullOrBlank() -> svc.findByViewId(params.str("view_id")!!)
        !params.str("text").isNullOrBlank() -> svc.findByText(params.str("text")!!)
        else -> null
    }

    private fun okGlobal(svc: JinAccessibilityService, action: Int, label: String): PluginResult {
        return if (svc.global(action)) PluginResult.ok(label) else PluginResult.fail("$label failed")
    }

    override suspend fun loadResources() {
        // Vision weights stay unloaded until describe() actually needs them.
    }

    override suspend fun unloadResources() {
        deps.vision.unloadNow()
    }
}

class ScreenAgentPluginFactory : PluginFactory {
    override fun create(deps: PluginDependencies): Plugin = ScreenAgentPlugin(deps)
}

internal fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull
        ?: this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
