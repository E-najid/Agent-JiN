package com.ngi.agentjin.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ngi.agentjin.AgentJinApp
import com.ngi.agentjin.core.download.DownloadProgress
import com.ngi.agentjin.core.download.ModelDownloadService
import com.ngi.agentjin.core.inference.ModelCatalog
import com.ngi.agentjin.core.inference.ModelCheck
import com.ngi.agentjin.core.inference.ModelFileStatus
import com.ngi.agentjin.core.planning.TaskPlan
import com.ngi.agentjin.core.safety.ConfirmRequest
import com.ngi.agentjin.core.safety.UserQuestionGate
import com.ngi.agentjin.core.screen.JinAccessibilityService
import com.ngi.agentjin.core.storage.ChatMessage
import com.ngi.agentjin.core.storage.MANIFEST_SCHEMA_VERSION
import com.ngi.agentjin.core.storage.UnlockResult
import com.ngi.agentjin.core.storage.WorkspaceManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppPhase { FOLDER, RESTORE, PASSWORD, UNLOCK, DOWNLOAD, READY }

data class UiState(
    val phase: AppPhase = AppPhase.FOLDER,
    val ramSummary: String = "",
    val nativeOk: Boolean = false,
    val nativeError: String? = null,
    val restoreCandidate: WorkspaceManifest? = null,
    val unlocked: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val conversationId: Long? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String = "",
    val busy: Boolean = false,
    val plan: TaskPlan? = null,
    val plugins: List<PluginInfo> = emptyList(),
    val comingSoon: String = "More plugins coming soon — voice, web search, messaging, connectors, terminal, and payments will be added later. Nothing listed here is a stub.",
    val confirm: ConfirmRequest? = null,
    val question: UserQuestionGate.Question? = null,
    val a11yEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val maxSteps: Int = 12,
    val confirmScreen: Boolean = true,
    val biometricEnabled: Boolean = false,
    val modelChecks: List<ModelCheck> = emptyList(),
    val download: DownloadProgress? = null,
    val lockRemainingMs: Long = 0L,
    val historyFiles: List<String> = emptyList(),
    val historyPreview: String = "",
)

data class PluginInfo(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val sensitive: Boolean,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as AgentJinApp).container
    private val _state = MutableStateFlow(
        UiState(
            ramSummary = c.ramPolicy.summary(),
            wifiOnly = c.devicePreferences.wifiOnlyDownloads,
            maxSteps = c.devicePreferences.maxStepsPerTask,
            confirmScreen = c.devicePreferences.confirmScreenActions,
            biometricEnabled = c.devicePreferences.biometricUnlockEnabled,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshPhase()
        viewModelScope.launch {
            repeat(40) {
                _state.update {
                    it.copy(nativeOk = c.llama.available, nativeError = c.llama.loadError)
                }
                if (c.llama.connected) return@launch
                kotlinx.coroutines.delay(250)
            }
        }
        viewModelScope.launch {
            c.confirmationGate.requests.collect { req ->
                _state.update { it.copy(confirm = req) }
            }
        }
        viewModelScope.launch {
            c.userQuestions.questions.collect { q ->
                _state.update { it.copy(question = q) }
            }
        }
        viewModelScope.launch {
            c.taskExecutor.plan.collect { p ->
                _state.update { it.copy(plan = p) }
            }
        }
        viewModelScope.launch {
            ModelDownloadService.progress.collect { p ->
                _state.update { it.copy(download = p) }
            }
        }
    }

    fun refreshPhase() {
        val hasFolder = c.storageRoot.hasPersistedTree()
        val manifest = runCatching { c.storageRoot.readManifest() }.getOrNull()
        val phase = when {
            !hasFolder -> AppPhase.FOLDER
            !c.memoryStore.isUnlocked && manifest != null && c.devicePreferences.setupCompleted -> AppPhase.UNLOCK
            !c.memoryStore.isUnlocked && manifest != null && !c.devicePreferences.setupCompleted -> AppPhase.RESTORE
            !c.memoryStore.isUnlocked -> AppPhase.PASSWORD
            else -> AppPhase.READY
        }
        _state.update {
            it.copy(
                phase = phase,
                restoreCandidate = manifest,
                unlocked = c.memoryStore.isUnlocked,
                a11yEnabled = JinAccessibilityService.isConnected(),
                lockRemainingMs = c.devicePreferences.remainingLockMs(),
            )
        }
        if (c.memoryStore.isUnlocked) {
            viewModelScope.launch { afterUnlock() }
        }
    }

    fun onFolderPicked() {
        refreshPhase()
    }

    fun chooseRestore(restore: Boolean) {
        if (restore) {
            _state.update { it.copy(phase = AppPhase.UNLOCK) }
        } else {
            _state.update { it.copy(phase = AppPhase.PASSWORD, restoreCandidate = null) }
        }
    }

    fun setupPassword(password: CharArray, confirm: CharArray) {
        if (!password.contentEquals(confirm)) {
            _state.update { it.copy(error = "Passwords do not match") }
            password.fill('\u0000'); confirm.fill('\u0000')
            return
        }
        if (password.size < 4) {
            _state.update { it.copy(error = "Use at least 4 characters") }
            password.fill('\u0000'); confirm.fill('\u0000')
            return
        }
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, status = "Deriving encryption key…") }
            try {
                withContext(Dispatchers.Default) {
                    c.storageRoot.ensureLayout()
                    c.session.initializeFresh(password)
                }
                c.conversations.openIfNeeded()
                _state.update { it.copy(error = null, unlocked = true, status = "") }
                afterUnlock()
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        busy = false,
                        status = "",
                        error = t.message ?: t.javaClass.simpleName,
                    )
                }
            } finally {
                password.fill('\u0000')
                confirm.fill('\u0000')
            }
        }
    }

    fun unlock(password: CharArray) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, status = "Unlocking…") }
            try {
                val r = withContext(Dispatchers.Default) { c.session.unlockWithPassword(password) }
                when (r) {
                    UnlockResult.Ok -> {
                        c.conversations.openIfNeeded()
                        afterUnlock()
                    }
                    is UnlockResult.BadPassword -> _state.update {
                        it.copy(
                            busy = false,
                            status = "",
                            error = "Wrong password (${r.attempts} failed attempts)",
                        )
                    }
                    is UnlockResult.LockedOut -> _state.update {
                        it.copy(
                            busy = false,
                            status = "",
                            error = "Locked. Try again in ${r.remainingMs / 1000}s",
                            lockRemainingMs = r.remainingMs,
                        )
                    }
                    is UnlockResult.Error -> _state.update {
                        it.copy(busy = false, status = "", error = r.message)
                    }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = false, status = "", error = t.message ?: t.javaClass.simpleName)
                }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    fun unlockWithKey(key: ByteArray) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, status = "Unlocking…") }
            try {
                when (val r = withContext(Dispatchers.Default) { c.session.unlockWithUnwrappedKey(key) }) {
                    UnlockResult.Ok -> {
                        c.conversations.openIfNeeded()
                        afterUnlock()
                    }
                    else -> _state.update { it.copy(busy = false, status = "", error = "Biometric unlock failed") }
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(busy = false, status = "", error = t.message ?: t.javaClass.simpleName)
                }
            } finally {
                key.fill(0)
            }
        }
    }

    fun enableBiometricCache() {
        runCatching { c.session.cacheKeyForBiometric() }
        _state.update { it.copy(biometricEnabled = c.devicePreferences.biometricUnlockEnabled) }
    }

    private suspend fun afterUnlock() {
        c.pluginManager.reload()
        val checks = c.modelManager.checkAll()
        val textOk = checks.any { it.spec.id == ModelCatalog.TEXT.id && it.status == ModelFileStatus.OK }
        val conv = c.conversations.listConversations().firstOrNull()
            ?: c.conversations.createConversation()
        val messages = c.conversations.messages(conv.id)
        val plugins = c.pluginManager.catalog().map { p ->
            PluginInfo(p.name, p.description, c.pluginManager.isEnabled(p.name), p.isSensitive)
        }
        if (textOk) {
            runCatching { c.textEngine.ensureLoaded() }
        }
        _state.update {
            it.copy(
                unlocked = true,
                busy = false,
                status = "",
                nativeOk = c.llama.available,
                nativeError = c.llama.loadError,
                phase = if (textOk) AppPhase.READY else AppPhase.DOWNLOAD,
                conversationId = conv.id,
                messages = messages,
                plugins = plugins,
                modelChecks = checks,
                ramSummary = c.ramPolicy.summary(),
                a11yEnabled = JinAccessibilityService.isConnected(),
                historyFiles = c.taskHistory.listTaskFiles(),
                error = null,
            )
        }
    }

    fun startDownload() {
        ModelDownloadService.start(getApplication(), listOf(ModelCatalog.TEXT.id))
        _state.update { it.copy(status = "Downloading text model…") }
        viewModelScope.launch {
            ModelDownloadService.running.collect { running ->
                if (!running) {
                    val checks = c.modelManager.checkAll()
                    val textOk = checks.any { it.spec.id == ModelCatalog.TEXT.id && it.status == ModelFileStatus.OK }
                    _state.update {
                        it.copy(
                            modelChecks = checks,
                            phase = if (textOk) AppPhase.READY else AppPhase.DOWNLOAD,
                            error = ModelDownloadService.lastError.value,
                        )
                    }
                }
            }
        }
    }

    fun send(text: String) {
        val id = _state.value.conversationId ?: return
        if (text.isBlank() || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, streaming = "", error = null, status = "Thinking…") }
            try {
                val reply = withContext(Dispatchers.Default) {
                    c.orchestrator.handleUserMessage(
                        conversationId = id,
                        userText = text.trim(),
                        onToken = { tok ->
                            _state.update { s -> s.copy(streaming = s.streaming + tok) }
                        },
                        onStatus = { st -> _state.update { s -> s.copy(status = st) } },
                    )
                }
                val messages = c.conversations.messages(id)
                _state.update {
                    it.copy(
                        messages = messages,
                        streaming = "",
                        busy = false,
                        status = "",
                        error = if (reply.startsWith("ERROR:")) reply else null,
                        historyFiles = c.taskHistory.listTaskFiles(),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = t.message ?: t.javaClass.simpleName,
                        status = "",
                    )
                }
            }
        }
    }

    fun answerConfirm(accept: Boolean) {
        val req = _state.value.confirm ?: return
        req.deferred.complete(accept)
        _state.update { it.copy(confirm = null) }
    }

    fun answerQuestion(text: String?) {
        val q = _state.value.question ?: return
        q.deferred.complete(text)
        _state.update { it.copy(question = null) }
    }

    fun setPluginEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch {
            c.pluginManager.setEnabled(name, enabled)
            val plugins = c.pluginManager.catalog().map { p ->
                PluginInfo(p.name, p.description, c.pluginManager.isEnabled(p.name), p.isSensitive)
            }
            _state.update { it.copy(plugins = plugins) }
        }
    }

    fun setWifiOnly(v: Boolean) {
        c.devicePreferences.wifiOnlyDownloads = v
        _state.update { it.copy(wifiOnly = v) }
    }

    fun setMaxSteps(v: Int) {
        c.devicePreferences.maxStepsPerTask = v
        _state.update { it.copy(maxSteps = c.devicePreferences.maxStepsPerTask) }
    }

    fun setConfirmScreen(v: Boolean) {
        c.devicePreferences.confirmScreenActions = v
        _state.update { it.copy(confirmScreen = v) }
    }

    fun undo() {
        viewModelScope.launch {
            val r = c.pluginManager.undoLast()
            _state.update { it.copy(status = r.message) }
        }
    }

    fun lock() {
        viewModelScope.launch {
            c.conversations.closeAndPersist()
            c.session.lock()
            c.textEngine.unload()
            c.visionEngine.unloadNow()
            _state.update { it.copy(unlocked = false, phase = AppPhase.UNLOCK, messages = emptyList()) }
        }
    }

    fun openHistory(name: String) {
        val id = name.removeSuffix(".jsonl")
        val events = c.taskHistory.readTask(id)
        _state.update {
            it.copy(historyPreview = events.joinToString("\n") { e ->
                "${e.at} ${e.kind} ${e.plugin ?: ""} ${e.message ?: ""}"
            })
        }
    }

    fun refreshA11y() {
        _state.update { it.copy(a11yEnabled = JinAccessibilityService.isConnected()) }
    }

    fun cancelTask() {
        c.taskExecutor.cancelRequested = true
        viewModelScope.launch { c.textEngine.abort() }
    }

    fun schemaVersion(): Int = MANIFEST_SCHEMA_VERSION
    fun textModelName(): String = ModelCatalog.TEXT.filename
    fun visionModelName(): String = ModelCatalog.VISION.filename
    fun hasBiometricWrap(): Boolean = c.secretStore.hasBiometricWrappedKey()
    fun wrapCipher() = c.secretStore.createBiometricUnlockCipher()
    fun unwrap(cipher: javax.crypto.Cipher) = c.secretStore.unwrapDerivedKey(cipher)
    val screenshots get() = c.screenshots
}
