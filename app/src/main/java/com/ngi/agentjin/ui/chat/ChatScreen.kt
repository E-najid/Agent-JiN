package com.ngi.agentjin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngi.agentjin.core.storage.ChatMessage
import com.ngi.agentjin.ui.plan.PlanChecklist
import com.ngi.agentjin.ui.theme.Gold
import com.ngi.agentjin.ui.theme.Purple
import com.ngi.agentjin.ui.theme.PurpleMid
import com.ngi.agentjin.ui.common.PrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenHistory: () -> Unit,
    onLock: () -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onAnswer: (String?) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.streaming) {
        val count = state.messages.size + if (state.streaming.isNotEmpty()) 1 else 0
        if (count > 0) runCatching { list.animateScrollToItem(count - 1) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent JiN") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Purple),
                actions = {
                    IconButton(onClick = onOpenPlugins) { Icon(Icons.Outlined.Extension, "Plugins") }
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Outlined.History, "History") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                    IconButton(onClick = onLock) { Icon(Icons.Outlined.Lock, "Lock") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
            if (state.status.isNotBlank()) {
                Text(state.status, color = Gold, style = MaterialTheme.typography.bodySmall)
            }
            if (state.error != null) {
                Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.plan?.let { PlanChecklist(it, Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages, key = { it.id }) { MessageBubble(it) }
                if (state.streaming.isNotBlank()) {
                    item { MessageBubble(ChatMessage(-1, 0, "assistant", state.streaming, 0)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask JiN to do something…") },
                    enabled = !state.busy,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    PrimaryButton(if (state.busy) "Working…" else "Send", enabled = !state.busy && draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                }
                if (state.busy) TextButton(onClick = onCancel) { Text("Stop") }
                TextButton(onClick = onUndo) { Text("Undo") }
            }
        }
    }
    state.confirm?.let { req ->
        AlertDialog(
            onDismissRequest = { onConfirm(false) },
            title = { Text(req.title) },
            text = { Text(req.message) },
            confirmButton = { TextButton(onClick = { onConfirm(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { onConfirm(false) }) { Text("Deny") } },
        )
    }
    state.question?.let { q ->
        var answer by remember(q.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { onAnswer(null) },
            title = { Text("JiN needs information") },
            text = {
                Column {
                    Text(q.prompt)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = answer, onValueChange = { answer = it })
                }
            },
            confirmButton = { TextButton(onClick = { onAnswer(answer) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val mine = msg.role == "user"
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalAlignment = if (mine) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start,
    ) {
        Text(
            text = msg.content,
            modifier = Modifier
                .background(if (mine) PurpleMid else Purple, RoundedCornerShape(16.dp))
                .padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
