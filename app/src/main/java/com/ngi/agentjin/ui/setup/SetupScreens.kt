package com.ngi.agentjin.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ngi.agentjin.core.download.DownloadProgress
import com.ngi.agentjin.core.inference.ModelCatalog
import com.ngi.agentjin.core.inference.ModelCheck
import com.ngi.agentjin.core.inference.ModelFileStatus
import com.ngi.agentjin.core.storage.WorkspaceManifest
import com.ngi.agentjin.ui.chat.UiState
import com.ngi.agentjin.ui.common.LabeledProgress
import com.ngi.agentjin.ui.common.PrimaryButton
import com.ngi.agentjin.ui.common.SecondaryButton
import com.ngi.agentjin.ui.common.SectionCard

@Composable
fun FolderSetupScreen(ram: String, nativeOk: Boolean, nativeError: String?, onPickFolder: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Agent JiN", style = MaterialTheme.typography.headlineLarge)
        Text(
            "On-device agent. Models, memories, and logs live in a folder you choose — including on an SD card — so they survive uninstall.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard("Storage", "Pick a folder via the Storage Access Framework. A persistable URI permission is taken so JiN can keep using it.") {
            PrimaryButton("Choose workspace folder", onClick = onPickFolder)
        }
        SectionCard("This device", ram)
        if (!nativeOk) {
            SectionCard(
                "Native runtime",
                "libagentjin_llama.so is not loaded (${nativeError ?: "unknown"}). Build the app with the Android NDK so llama.cpp can compile. The rest of the app still runs; generation will report this error instead of inventing replies.",
            )
        }
    }
}

@Composable
fun RestoreScreen(manifest: WorkspaceManifest, onRestore: () -> Unit, onFresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Existing workspace", style = MaterialTheme.typography.headlineMedium)
        Text("This folder already has manifest.json (schema ${manifest.schemaVersion}). Restore it, or set a new password (old memories stay encrypted with the old password).")
        SectionCard("Models in manifest", manifest.models.entries.joinToString("\n") { (k, v) -> "$k: ${v.name}" })
        PrimaryButton("Restore this workspace", onClick = onRestore)
        SecondaryButton("Start fresh password on this folder", onClick = onFresh)
    }
}

@Composable
fun PasswordSetupScreen(error: String?, busy: Boolean, status: String, onSubmit: (CharArray, CharArray) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Master password", style = MaterialTheme.typography.headlineMedium)
        Text("Argon2id derives the AES-256-GCM key. The password is never stored. Without it, memories cannot be read — including by us.")
        OutlinedTextField(
            value = a,
            onValueChange = { a = it },
            label = { Text("Password / PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !busy,
        )
        OutlinedTextField(
            value = b,
            onValueChange = { b = it },
            label = { Text("Confirm") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !busy,
        )
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        PrimaryButton(if (busy) "Working…" else "Create workspace", enabled = !busy) {
            onSubmit(a.toCharArray(), b.toCharArray())
        }
    }
}

@Composable
fun UnlockScreen(
    error: String?,
    lockMs: Long,
    biometric: Boolean,
    busy: Boolean,
    status: String,
    onUnlock: (CharArray) -> Unit,
    onBiometric: () -> Unit,
) {
    var a by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Unlock", style = MaterialTheme.typography.headlineMedium)
        Text("Enter the master password for this workspace.")
        OutlinedTextField(
            value = a,
            onValueChange = { a = it },
            label = { Text("Password / PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !busy,
        )
        if (lockMs > 0) Text("Locked for ${lockMs / 1000}s", color = MaterialTheme.colorScheme.error)
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        PrimaryButton(
            if (busy) "Working…" else "Unlock",
            enabled = lockMs <= 0L && !busy,
        ) { onUnlock(a.toCharArray()) }
        if (biometric) SecondaryButton("Use biometrics", enabled = !busy) { onBiometric() }
    }
}

@Composable
fun DownloadScreen(state: UiState, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("On-device models", style = MaterialTheme.typography.headlineMedium)
        Text("The text model (~220 MiB) is enough to chat. Vision models are optional and downloaded later from Settings. Wi-Fi only is ${if (state.wifiOnly) "on" else "off"}.")
        state.modelChecks.forEach { check ->
            ModelRow(check)
        }
        state.download?.let { d ->
            LabeledProgress("${d.phase} ${d.filename}", d.fraction)
            if (d.error != null) Text(d.error, color = MaterialTheme.colorScheme.error)
        }
        if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
        PrimaryButton("Download text model", onClick = onStart)
        Spacer(Modifier.height(8.dp))
        Text("If every source fails, the error lists the exact filename, SHA-256, and manual copy instructions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelCatalog.ALL.forEach {
            Text("• ${it.filename}  ${it.sizeBytes / 1024 / 1024} MiB", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelRow(check: ModelCheck) {
    val label = when (check.status) {
        ModelFileStatus.OK -> "ready"
        ModelFileStatus.MISSING -> "missing"
        ModelFileStatus.CORRUPT -> "checksum mismatch"
        ModelFileStatus.UNKNOWN -> "unknown"
    }
    SectionCard(check.spec.displayName, "${check.spec.filename}\n$label\nsha256=${check.spec.sha256}")
}
