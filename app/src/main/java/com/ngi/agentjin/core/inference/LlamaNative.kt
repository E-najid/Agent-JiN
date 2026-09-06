package com.ngi.agentjin.core.inference

/**
 * JNI surface for llama.cpp. Loaded from libagentjin_llama.so.
 */
class LlamaNative {
    interface TokenCallback {
        fun onToken(piece: String)
        fun shouldStop(): Boolean
    }

    external fun initBackend()
    external fun loadModel(path: String, nCtx: Int, nThreads: Int, useMmap: Boolean): Long
    external fun loadMmproj(handle: Long, path: String): Boolean
    external fun unload(handle: Long)
    external fun abort(handle: Long)
    external fun reset(handle: Long)
    external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        stops: String,
        grammar: String,
        callback: TokenCallback?,
    ): String
    external fun generateVision(
        handle: Long,
        prompt: String,
        rgb: ByteArray,
        width: Int,
        height: Int,
        maxTokens: Int,
        temp: Float,
        stops: String,
    ): String
    external fun systemInfo(): String

    companion object {
        @Volatile
        var available: Boolean = false
            private set

        @Volatile
        var loadError: String? = null
            private set

        init {
            try {
                System.loadLibrary("agentjin_llama")
                available = true
            } catch (t: Throwable) {
                available = false
                loadError = t.message ?: t.javaClass.simpleName
            }
        }
    }
}
