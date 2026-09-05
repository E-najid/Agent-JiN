package com.ngi.agentjin.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.agentjin.ui.common.SectionCard

@Composable
fun TaskHistoryScreen(
    files: List<String>,
    preview: String,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text("Task history", style = MaterialTheme.typography.headlineMedium)
        Text("Every plugin call is logged by permission_guard into /task_history.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (files.isEmpty()) {
            SectionCard("Empty", "No tasks have been executed yet. This is not dummy data.")
        } else {
            files.forEach { name ->
                Text(name, modifier = Modifier.clickable { onOpen(name) }.padding(8.dp))
            }
        }
        if (preview.isNotBlank()) {
            SectionCard("Log", preview)
        }
    }
}
