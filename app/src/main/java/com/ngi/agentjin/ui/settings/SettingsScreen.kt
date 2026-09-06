package com.ngi.agentjin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.agentjin.ui.chat.UiState
import com.ngi.agentjin.ui.common.PrimaryButton
import com.ngi.agentjin.ui.common.SectionCard

@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onMaxSteps: (Int) -> Unit,
    onConfirmScreen: (Boolean) -> Unit,
    onEnableA11y: () -> Unit,
    onScreenCapture: () -> Unit,
    onEnableBiometric: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        SectionCard("Device", state.ramSummary)
        SectionCard("Downloads", "Resumable HTTP Range, SHA-256 verify, Wi-Fi only by default.") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Wi-Fi only", modifier = Modifier.weight(1f))
                Switch(checked = state.wifiOnly, onCheckedChange = onWifiOnly)
            }
            PrimaryButton("Download / verify models", onClick = onDownload)
        }
        SectionCard("Planning", "Max steps per task: ${state.maxSteps}") {
            Slider(
                value = state.maxSteps.toFloat(),
                onValueChange = { onMaxSteps(it.toInt()) },
                valueRange = 1f..32f,
                steps = 30,
            )
        }
        SectionCard("Safety", "Screen actions require a confirmation dialog when this is on.") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Confirm screen actions", modifier = Modifier.weight(1f))
                Switch(checked = state.confirmScreen, onCheckedChange = onConfirmScreen)
            }
        }
        SectionCard(
            "Accessibility",
            if (state.a11yEnabled) "Screen agent service is connected."
            else "Not connected. JiN cannot tap or read the UI tree until you enable it.",
        ) {
            PrimaryButton(if (state.a11yEnabled) "Open accessibility settings" else "Enable screen agent", onClick = onEnableA11y)
        }
        SectionCard("Screenshot fallback", "MediaProjection is used when the accessibility tree is insufficient.") {
            PrimaryButton("Grant screen capture", onClick = onScreenCapture)
        }
        SectionCard("Biometric unlock", "Caches the derived key in Android Keystore on this device only. It does not replace the password.") {
            PrimaryButton("Enable biometric unlock after next password unlock", onClick = onEnableBiometric)
        }
        if (!state.nativeOk) {
            SectionCard("llama.cpp", "Native library missing: ${state.nativeError}. Generation is disabled, not faked.")
        }
    }
}
