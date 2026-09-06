package com.ngi.agentjin.ui.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.agentjin.ui.chat.PluginInfo
import com.ngi.agentjin.ui.common.SectionCard

@Composable
fun PluginsScreen(
    plugins: List<PluginInfo>,
    comingSoon: String,
    onToggle: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Plugins", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Each plugin is independently toggleable. Disabled plugins are not loaded, so their models and resources stay on disk.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        plugins.forEach { p ->
            SectionCard(p.name, p.description + if (p.sensitive) "\nRequires confirmation." else "") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (p.enabled) "Enabled" else "Disabled", modifier = Modifier.weight(1f))
                    Switch(checked = p.enabled, onCheckedChange = { onToggle(p.name, it) })
                }
            }
        }
        SectionCard("More plugins coming soon", comingSoon)
    }
}
