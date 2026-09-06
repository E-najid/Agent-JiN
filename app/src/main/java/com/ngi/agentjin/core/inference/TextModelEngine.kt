package com.ngi.agentjin.core.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ModelNotReadyException(message: String) : IllegalStateException(message)

class TextModelEngine(
    private val models: ModelManager,
    private val ram: RamPolicy,
) {
    private val native = LlamaNative()
    private val mutex = Mutex()
    private var handle: Long = 0L

    val isLoaded: Boolean get() = handle != 0L

    suspend fun ensureLoaded() = mutex.withLock {
        if (handle != 0L) return
        if (!LlamaNative.available) {
            throw ModelNotReadyException(
                "Native llama.cpp library is not loaded: ${LlamaNative.loadError ?: "unknown error"}",
            )
        }
        withContext(Dispatchers.Default) {
            native.initBackend()
            val file = models.localFileFor(ModelCatalog.TEXT)
                ?: throw ModelNotReadyException("Text model file is missing or checksum failed. Download it from Settings.")
            val h = native.loadModel(file.absolutePath, ram.textContextSize, ram.nThreads, ram.useMmap)
            if (h == 0L) {
                throw ModelNotReadyException("llama.cpp failed to load ${ModelCatalog.TEXT.filename}")
            }
            handle = h
        }
    }

    suspend fun unload() = mutex.withLock {
        if (handle != 0L) {
            native.unload(handle)
            handle = 0L
        }
    }

    suspend fun abort() {
        val h = handle
        if (h != 0L) native.abort(h)
    }

    suspend fun complete(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.3f,
        stops: List<String> = DEFAULT_STOPS,
        grammar: String? = null,
        onToken: ((String) -> Unit)? = null,
        shouldStop: () -> Boolean = { false },
    ): String = mutex.withLock {
        if (handle == 0L) {
            throw ModelNotReadyException("Text model is not loaded")
        }
        withContext(Dispatchers.Default) {
            val cb = if (onToken == null) null else object : LlamaNative.TokenCallback {
                override fun onToken(piece: String) {
                    onToken(piece)
                }
                override fun shouldStop(): Boolean = shouldStop()
            }
            native.generate(
                handle,
                prompt,
                maxTokens,
                temperature,
                stops.joinToString("\n"),
                grammar.orEmpty(),
                cb,
            )
        }
    }

    companion object {
        val DEFAULT_STOPS = listOf("<|im_end|>", "<|endoftext|>", "</s>")
    }
}
