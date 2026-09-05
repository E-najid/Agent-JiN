package com.ngi.agentjin.plugins.settings

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.ngi.agentjin.core.plugin.JsonSchema
import com.ngi.agentjin.core.plugin.JsonSchemaProperty
import com.ngi.agentjin.core.plugin.Plugin
import com.ngi.agentjin.core.plugin.PluginDependencies
import com.ngi.agentjin.core.plugin.PluginFactory
import com.ngi.agentjin.core.plugin.PluginResult
import com.ngi.agentjin.plugins.screen.int
import com.ngi.agentjin.plugins.screen.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class SettingsPlugin(
    private val deps: PluginDependencies,
) : Plugin {
    override val name = "settings"
    override val description = "Read or change Wi-Fi, Bluetooth, and screen brightness. On modern Android, Wi-Fi/Bluetooth toggles open the system panel because apps are not allowed to flip those radios directly."
    override val isSensitive = true
    override val parameters = JsonSchema(
        properties = mapOf(
            "action" to JsonSchemaProperty(
                type = "string",
                enumValues = listOf("status", "wifi", "bluetooth", "brightness"),
            ),
            "enabled" to JsonSchemaProperty("boolean", "For wifi/bluetooth: desired state"),
            "value" to JsonSchemaProperty("integer", "For brightness: 0–255", minimum = 0.0, maximum = 255.0),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val action = params.str("action") ?: return PluginResult.fail("missing action")
        return when (action) {
            "status" -> status()
            "wifi" -> wifi(params.bool("enabled"))
            "bluetooth" -> bluetooth(params.bool("enabled"))
            "brightness" -> brightness(params.int("value"))
            else -> PluginResult.unimplemented("settings action '$action'")
        }
    }

    private fun status(): PluginResult {
        val wifi = deps.appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val bt = (deps.appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val cr = deps.appContext.contentResolver
        val bright = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        return PluginResult.ok(
            "wifi=${wifi.isWifiEnabled} bluetooth=${bt?.isEnabled} brightness=$bright",
            buildJsonObject {
                put("wifi", JsonPrimitive(wifi.isWifiEnabled))
                put("bluetooth", JsonPrimitive(bt?.isEnabled == true))
                put("brightness", JsonPrimitive(bright))
                put("can_write_settings", JsonPrimitive(Settings.System.canWrite(deps.appContext)))
            },
        )
    }

    private fun wifi(enabled: Boolean?): PluginResult {
        if (enabled == null) return PluginResult.fail("missing enabled")
        val wifi = deps.appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val previous = wifi.isWifiEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panel = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            deps.appContext.startActivity(panel)
            return PluginResult(
                ok = false,
                message = "Android 10+ does not allow apps to toggle Wi-Fi. Opened the system Wi-Fi panel; flip it there.",
                data = buildJsonObject { put("previous", JsonPrimitive(previous)) },
            )
        }
        @Suppress("DEPRECATION")
        val ok = wifi.setWifiEnabled(enabled)
        return if (ok) {
            PluginResult.ok(
                "Wi-Fi ${if (enabled) "enabled" else "disabled"}",
                canUndo = true,
                undoToken = "wifi:$previous",
            )
        } else {
            PluginResult.fail("WifiManager.setWifiEnabled returned false")
        }
    }

    private fun bluetooth(enabled: Boolean?): PluginResult {
        if (enabled == null) return PluginResult.fail("missing enabled")
        val adapter = (deps.appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return PluginResult.fail("This device has no Bluetooth adapter")
        val previous = adapter.isEnabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val i = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            deps.appContext.startActivity(i)
            return PluginResult(
                ok = false,
                message = "Android 13+ does not allow apps to toggle Bluetooth. Opened Bluetooth settings; flip it there.",
                data = buildJsonObject { put("previous", JsonPrimitive(previous)) },
            )
        }
        @Suppress("DEPRECATION")
        val ok = if (enabled) adapter.enable() else adapter.disable()
        return if (ok) {
            PluginResult.ok(
                "Bluetooth ${if (enabled) "enable requested" else "disable requested"}",
                canUndo = true,
                undoToken = "bt:$previous",
            )
        } else {
            PluginResult.fail("BluetoothAdapter enable/disable returned false (missing BLUETOOTH_CONNECT?)")
        }
    }

    private fun brightness(value: Int?): PluginResult {
        if (value == null) return PluginResult.fail("missing value")
        val v = value.coerceIn(0, 255)
        if (!Settings.System.canWrite(deps.appContext)) {
            val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = android.net.Uri.parse("package:${deps.appContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            deps.appContext.startActivity(i)
            return PluginResult.fail("WRITE_SETTINGS is not granted. Opened the system screen so you can allow it, then retry.")
        }
        val cr = deps.appContext.contentResolver
        val previous = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
        val ok = Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        return if (ok) {
            PluginResult.ok("Brightness set to $v", canUndo = true, undoToken = "brightness:$previous")
        } else {
            PluginResult.fail("Settings.System.putInt returned false")
        }
    }

    override suspend fun undo(token: String): PluginResult {
        val parts = token.split(":")
        if (parts.size != 2) return PluginResult.fail("bad undo token")
        return when (parts[0]) {
            "wifi" -> wifi(parts[1].toBooleanStrict())
            "bt" -> bluetooth(parts[1].toBooleanStrict())
            "brightness" -> brightness(parts[1].toInt())
            else -> PluginResult.fail("unknown undo token")
        }
    }
}

private fun JsonObject.bool(key: String): Boolean? {
    val p = this[key]?.jsonPrimitive ?: return null
    p.booleanOrNull?.let { return it }
    return p.content.toBooleanStrictOrNull()
}

class SettingsPluginFactory : PluginFactory {
    override fun create(deps: PluginDependencies): Plugin = SettingsPlugin(deps)
}
