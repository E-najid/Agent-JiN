package com.ngi.agentjin.core.plugin

import android.content.Context
import com.ngi.agentjin.core.inference.VisionModelEngine
import com.ngi.agentjin.core.screen.ScreenPerception
import com.ngi.agentjin.core.screen.ScreenshotCapture
import com.ngi.agentjin.core.storage.DevicePreferences
import com.ngi.agentjin.core.storage.SecretStore

/**
 * Services plugins may use. Grow this type as new shared capabilities appear;
 * existing plugins compile against default/unused fields.
 */
class PluginDependencies(
    val appContext: Context,
    val screen: ScreenPerception,
    val screenshots: ScreenshotCapture,
    val vision: VisionModelEngine,
    val secrets: SecretStore,
    val devicePreferences: DevicePreferences,
)
