package com.ngi.agentjin.core.inference

import kotlinx.coroutines.CoroutineScope

/**
 * Vision stays unloaded in the UI process. libmtmd is not linked; calling
 * llama from here would load the .so in the UI process and a native abort
 * would kill the chat screen.
 */
class VisionModelEngine(
    @Suppress("unused") private val models: ModelManager,
    @Suppress("unused") private val ram: RamPolicy,
    @Suppress("unused") private val scope: CoroutineScope,
) {
    val isLoaded: Boolean get() = false

    suspend fun describeScreen(
        rgb: ByteArray,
        width: Int,
        height: Int,
        question: String,
    ): String {
        return "ERROR: vision runtime (libmtmd) was not compiled into this build"
    }

    suspend fun unloadNow() = Unit
}
