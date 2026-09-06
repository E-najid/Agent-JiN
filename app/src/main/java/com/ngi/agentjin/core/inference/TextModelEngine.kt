package com.ngi.agentjin.core.inference

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ModelNotReadyException(message: String) : IllegalStateException(message)

class TextModelEngine(
    private val models: ModelManager,
    private val ram: RamPolicy,
    private val llama: LlamaClient,
) {
    private val mutex = Mutex()
    private var loaded = false

    val isLoaded: Boolean get() = loaded

    suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return
        if (!llama.connected) {
            throw ModelNotReadyException(
                llama.loadError ?: "Native llama process is not bound yet",
            )
        }
        val file = models.localFileFor(ModelCatalog.TEXT)
            ?: throw ModelNotReadyException(
                "Text model file is missing. Download LFM2.5-350M-Q4_K_M.gguf first.",
            )
        llama.load(file.absolutePath, ram.textContextSize, ram.nThreads, ram.useMmap)
        loaded = true
    }

    suspend fun unload() = mutex.withLock {
        llama.unload()
        loaded = false
    }

    suspend fun abort() {
        llama.abort()
    }

    suspend fun complete(
        prompt: String,
        maxTokens: Int = 96,
        temperature: Float = 0.4f,
        stops: List<String> = DEFAULT_STOPS,
        grammar: String? = null,
        onToken: ((String) -> Unit)? = null,
        shouldStop: () -> Boolean = { false },
    ): String = mutex.withLock {
        if (!loaded) {
            throw ModelNotReadyException("Text model is not loaded")
        }
        // grammar / onToken ignored: both were crash sources (GBNF hang, JNI UTF-8).
        llama.generate(
            prompt,
            maxTokens.coerceIn(8, 128),
            temperature,
            stops.joinToString("\n"),
        )
    }

    companion object {
        val DEFAULT_STOPS = listOf("<|im_end|>", "<|endoftext|>", "</s>")
    }
}
