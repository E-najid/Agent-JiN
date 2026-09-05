package com.ngi.agentjin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ngi.agentjin.ui.chat.AppPhase
import com.ngi.agentjin.ui.chat.ChatScreen
import com.ngi.agentjin.ui.chat.ChatViewModel
import com.ngi.agentjin.ui.history.TaskHistoryScreen
import com.ngi.agentjin.ui.plugins.PluginsScreen
import com.ngi.agentjin.ui.settings.SettingsScreen
import com.ngi.agentjin.ui.setup.DownloadScreen
import com.ngi.agentjin.ui.setup.FolderSetupScreen
import com.ngi.agentjin.ui.setup.PasswordSetupScreen
import com.ngi.agentjin.ui.setup.RestoreScreen
import com.ngi.agentjin.ui.setup.UnlockScreen
import com.ngi.agentjin.ui.theme.AgentJinTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : FragmentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            val state by vm.state.collectAsState()
            var page by remember { mutableStateOf("main") }
            val folderLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree(),
            ) { uri: Uri? ->
                if (uri != null) {
                    (application as AgentJinApp).container.storageRoot.takePersistablePermission(uri)
                    vm.onFolderPicked()
                }
            }
            val captureLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                vm.screenshots.onPermissionResult(result.resultCode, result.data)
            }
            AgentJinTheme {
                Surface(Modifier.fillMaxSize()) {
                    when {
                        page == "settings" -> SettingsScreen(
                            state = state,
                            onBack = { page = "main" },
                            onWifiOnly = vm::setWifiOnly,
                            onMaxSteps = vm::setMaxSteps,
                            onConfirmScreen = vm::setConfirmScreen,
                            onEnableA11y = { openAccessibilitySettings() },
                            onScreenCapture = {
                                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                captureLauncher.launch(mpm.createScreenCaptureIntent())
                            },
                            onEnableBiometric = { vm.enableBiometricCache() },
                            onDownload = { vm.startDownload(); page = "main" },
                        )
                        page == "plugins" -> PluginsScreen(
                            plugins = state.plugins,
                            comingSoon = state.comingSoon,
                            onToggle = vm::setPluginEnabled,
                            onBack = { page = "main" },
                        )
                        page == "history" -> TaskHistoryScreen(
                            files = state.historyFiles,
                            preview = state.historyPreview,
                            onOpen = vm::openHistory,
                            onBack = { page = "main" },
                        )
                        state.phase == AppPhase.FOLDER -> FolderSetupScreen(
                            ram = state.ramSummary,
                            nativeOk = state.nativeOk,
                            nativeError = state.nativeError,
                            onPickFolder = { folderLauncher.launch(null) },
                        )
                        state.phase == AppPhase.RESTORE && state.restoreCandidate != null -> RestoreScreen(
                            manifest = state.restoreCandidate!!,
                            onRestore = { vm.chooseRestore(true) },
                            onFresh = { vm.chooseRestore(false) },
                        )
                        state.phase == AppPhase.PASSWORD -> PasswordSetupScreen(
                            error = state.error,
                            onSubmit = vm::setupPassword,
                        )
                        state.phase == AppPhase.UNLOCK -> UnlockScreen(
                            error = state.error,
                            lockMs = state.lockRemainingMs,
                            biometric = state.biometricEnabled && vm.hasBiometricWrap(),
                            onUnlock = vm::unlock,
                            onBiometric = { promptBiometric() },
                        )
                        state.phase == AppPhase.DOWNLOAD -> DownloadScreen(
                            state = state,
                            onStart = vm::startDownload,
                        )
                        else -> ChatScreen(
                            state = state,
                            onSend = vm::send,
                            onOpenSettings = { page = "settings"; vm.refreshA11y() },
                            onOpenPlugins = { page = "plugins" },
                            onOpenHistory = { page = "history" },
                            onLock = vm::lock,
                            onUndo = vm::undo,
                            onCancel = vm::cancelTask,
                            onConfirm = vm::answerConfirm,
                            onAnswer = vm::answerQuestion,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshA11y()
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 76)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun promptBiometric() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val cipher = result.cryptoObject?.cipher ?: return
                    val key = vm.unwrap(cipher)
                    vm.unlockWithKey(key)
                }
            },
        )
        val cipher = try {
            vm.wrapCipher()
        } catch (_: Exception) {
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Agent JiN")
            .setSubtitle("Biometrics unwrap the derived key cached on this device")
            .setNegativeButtonText("Use password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
