package com.ngi.agentjin.core.di

import android.app.Application
import com.ngi.agentjin.core.crypto.CryptoEngine
import com.ngi.agentjin.core.download.ModelDownloader
import com.ngi.agentjin.core.inference.LlamaClient
import com.ngi.agentjin.core.inference.ModelManager
import com.ngi.agentjin.core.inference.RamPolicy
import com.ngi.agentjin.core.inference.TextModelEngine
import com.ngi.agentjin.core.inference.VisionModelEngine
import com.ngi.agentjin.core.planning.Orchestrator
import com.ngi.agentjin.core.planning.Planner
import com.ngi.agentjin.core.planning.TaskExecutor
import com.ngi.agentjin.core.planning.TaskHistoryStore
import com.ngi.agentjin.core.plugin.PluginDependencies
import com.ngi.agentjin.core.plugin.PluginManager
import com.ngi.agentjin.core.safety.ConfirmationGate
import com.ngi.agentjin.core.safety.PermissionGuard
import com.ngi.agentjin.core.safety.UndoManager
import com.ngi.agentjin.core.safety.UserQuestionGate
import com.ngi.agentjin.core.screen.ScreenPerception
import com.ngi.agentjin.core.screen.ScreenshotCapture
import com.ngi.agentjin.core.storage.ConversationRepository
import com.ngi.agentjin.core.storage.DevicePreferences
import com.ngi.agentjin.core.storage.EncryptedMemoryStore
import com.ngi.agentjin.core.storage.SecretStore
import com.ngi.agentjin.core.storage.Session
import com.ngi.agentjin.core.storage.StorageRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val devicePreferences = DevicePreferences(app)
    val secretStore = SecretStore(app)
    val crypto = CryptoEngine()
    val ramPolicy = RamPolicy(app)
    val storageRoot = StorageRoot(app, devicePreferences)
    val memoryStore = EncryptedMemoryStore(app, storageRoot, crypto)
    val session = Session(crypto, storageRoot, memoryStore, devicePreferences, secretStore)
    val conversations = ConversationRepository(app, memoryStore)
    val taskHistory = TaskHistoryStore(storageRoot)
    val permissionGuard = PermissionGuard(taskHistory)
    val confirmationGate = ConfirmationGate()
    val userQuestions = UserQuestionGate()
    val undoManager = UndoManager()
    val modelManager = ModelManager(storageRoot, ramPolicy, app.filesDir)
    val llama = LlamaClient(app).also { it.bind() }
    val textEngine = TextModelEngine(modelManager, ramPolicy, llama)
    val visionEngine = VisionModelEngine(modelManager, ramPolicy, scope)
    val screen = ScreenPerception()
    val screenshots = ScreenshotCapture(app)
    val pluginDeps = PluginDependencies(
        appContext = app,
        screen = screen,
        screenshots = screenshots,
        vision = visionEngine,
        secrets = secretStore,
        devicePreferences = devicePreferences,
    )
    val pluginManager = PluginManager(
        deps = pluginDeps,
        memory = memoryStore,
        permissionGuard = permissionGuard,
        confirmationGate = confirmationGate,
        undoManager = undoManager,
    )
    val planner = Planner(textEngine, pluginManager)
    val taskExecutor = TaskExecutor(
        plugins = pluginManager,
        planner = planner,
        history = taskHistory,
        screen = screen,
        questions = userQuestions,
        maxSteps = { devicePreferences.maxStepsPerTask },
    )
    val orchestrator = Orchestrator(
        text = textEngine,
        planner = planner,
        executor = taskExecutor,
        conversations = conversations,
        screen = screen,
        questions = userQuestions,
    )
    val downloader = ModelDownloader(
        context = app,
        storage = storageRoot,
        models = modelManager,
        wifiOnly = { devicePreferences.wifiOnlyDownloads },
    )
}
