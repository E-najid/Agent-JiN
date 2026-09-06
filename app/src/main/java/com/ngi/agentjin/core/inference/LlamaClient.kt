package com.ngi.agentjin.core.inference

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * UI-process handle to [LlamaService]. Never loads libagentjin_llama.so here.
 */
class LlamaClient(private val app: Application) {
    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    val available: Boolean get() = connected && loadError == null

    private var sendTo: Messenger? = null
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val seq = AtomicInteger(1)
    private val rpcMu = Mutex()
    private var loadedPath: String? = null

    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            val id = msg.arg2
            val text = msg.data.getString("text").orEmpty()
            val d = waiters.remove(id)
            if (msg.what == LlamaService.MSG_ERR) {
                d?.completeExceptionally(IllegalStateException(text))
            } else {
                d?.complete(text)
            }
            true
        },
    )

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sendTo = Messenger(service)
            connected = true
            loadError = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sendTo = null
            connected = false
            loadedPath = null
            loadError = "llama process crashed (native). The chat UI stayed up — send again."
            waiters.values.forEach {
                it.completeExceptionally(IllegalStateException(loadError))
            }
            waiters.clear()
            bind()
        }
    }

    fun bind() {
        val so = File(app.applicationInfo.nativeLibraryDir, "libagentjin_llama.so")
        if (!so.exists()) {
            loadError = "libagentjin_llama.so is not in this APK (${app.applicationInfo.nativeLibraryDir})"
            return
        }
        try {
            app.bindService(Intent(app, LlamaService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
        }
    }

    suspend fun ping() = rpc(LlamaService.MSG_PING, Bundle(), timeoutMs = 15_000)

    suspend fun load(path: String, nCtx: Int, nThreads: Int, mmap: Boolean) = rpcMu.withLock {
        if (loadedPath == path) return@withLock
        val b = Bundle().apply {
            putString("path", path)
            putInt("nCtx", nCtx)
            putInt("nThreads", nThreads)
            putBoolean("mmap", mmap)
        }
        rpc(LlamaService.MSG_LOAD, b, timeoutMs = 120_000)
        loadedPath = path
    }

    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temp: Float,
        stops: String,
    ): String = rpcMu.withLock {
        val b = Bundle().apply {
            putString("prompt", prompt)
            putInt("maxTokens", maxTokens)
            putFloat("temp", temp)
            putString("stops", stops)
        }
        rpc(LlamaService.MSG_GEN, b, timeoutMs = 180_000)
    }

    suspend fun abort() {
        try {
            rpc(LlamaService.MSG_ABORT, Bundle(), timeoutMs = 5_000)
        } catch (_: Throwable) {
        }
    }

    suspend fun unload() = rpcMu.withLock {
        try {
            rpc(LlamaService.MSG_UNLOAD, Bundle(), timeoutMs = 15_000)
        } finally {
            loadedPath = null
        }
    }

    private suspend fun rpc(what: Int, bundle: Bundle, timeoutMs: Long): String =
        withContext(Dispatchers.IO) {
            val dest = sendTo ?: throw IllegalStateException(loadError ?: "llama service not bound yet")
            val id = seq.getAndIncrement()
            val d = CompletableDeferred<String>()
            waiters[id] = d
            val msg = Message.obtain(null, what)
            msg.data = bundle
            msg.arg2 = id
            msg.replyTo = incoming
            dest.send(msg)
            try {
                withTimeout(timeoutMs) { d.await() }
            } catch (t: Throwable) {
                waiters.remove(id)
                throw t
            }
        }
}
