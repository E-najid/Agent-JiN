package com.ngi.agentjin.core.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Loaded on demand, unloaded when idle. On constrained (≤3GB) devices it is
 * never kept resident after a call returns.
 */
class VisionModelEngine(
    private val models: ModelManager,
    private val ram: RamPolicy,
    private val scope: CoroutineScope,
) {
    private val native = LlamaNative()
    private val mutex = Mutex()
    private var handle: Long = 0L
    private var unloadJob: Job? = null

    val isLoaded: Boolean get() = handle != 0L

    suspend fun describeScreen(
        rgb: ByteArray,
        width: Int,
        height: Int,
        question: String,
    ): String {
        mutex.withLock { loadLocked() }
        return try {
            mutex.withLock {
                if (handle == 0L) {
                    throw ModelNotReadyException("Vision model is not loaded")
                }
                withContext(Dispatchers.Default) {
                    native.generateVision(
                        handle,
                        question,
                        rgb,
                        width,
                        height,
                        192,
                        0.1f,
                        TextModelEngine.DEFAULT_STOPS.joinToString("\n"),
                    )
                }
            }
        } finally {
            scheduleUnload()
        }
    }

    suspend fun unloadNow() = mutex.withLock { unloadLocked() }

    private suspend fun loadLocked() {
        if (handle != 0L) return
        if (!LlamaNative.available) {
            throw ModelNotReadyException(
                "Native llama.cpp library is not loaded: ${LlamaNative.loadError ?: "unknown error"}",
            )
        }
        native.initBackend()
        val weights = models.localFileFor(ModelCatalog.VISION)
            ?: throw ModelNotReadyException("Vision model file is missing or checksum failed.")
        val proj = models.localFileFor(ModelCatalog.VISION_PROJ)
            ?: throw ModelNotReadyException("Vision mmproj file is missing or checksum failed.")
        val h = native.loadModel(weights.absolutePath, ram.visionContextSize, ram.nThreads, ram.useMmap)
        if (h == 0L) {
            throw ModelNotReadyException("llama.cpp failed to load ${ModelCatalog.VISION.filename}")
        }
        val ok = native.loadMmproj(h, proj.absolutePath)
        if (!ok) {
            native.unload(h)
            throw ModelNotReadyException("Failed to load mmproj ${ModelCatalog.VISION_PROJ.filename}")
        }
        handle = h
    }

    private fun unloadLocked() {
        if (handle != 0L) {
            native.unload(handle)
            handle = 0L
        }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        val delayMs = if (ram.keepVisionResident) ram.visionIdleUnloadMs else 0L
        unloadJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            mutex.withLock { unloadLocked() }
        }
    }
}
