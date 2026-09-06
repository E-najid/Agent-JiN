package com.ngi.agentjin.core.inference

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * Runs llama.cpp in a separate process (`:llama`). A native abort then kills
 * only this process; the UI can show an error instead of disappearing.
 */
class LlamaService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var native: LlamaNative? = null
    private var handle: Long = 0L

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("llama-work").apply { start() }
        worker = Handler(thread.looper) { msg ->
            val replyTo = msg.replyTo
            val id = msg.arg2
            try {
                when (msg.what) {
                    MSG_PING -> {
                        ensureNative()
                        reply(replyTo, id, MSG_OK, "ok")
                    }
                    MSG_LOAD -> {
                        ensureNative()
                        val b = msg.data
                        val path = b.getString("path") ?: error("missing path")
                        if (handle != 0L) {
                            native!!.unload(handle)
                            handle = 0L
                        }
                        val h = native!!.loadModel(
                            path,
                            b.getInt("nCtx", 256),
                            b.getInt("nThreads", 1),
                            b.getBoolean("mmap", true),
                        )
                        if (h == 0L) error("llama.cpp failed to load $path")
                        handle = h
                        reply(replyTo, id, MSG_OK, "loaded")
                    }
                    MSG_GEN -> {
                        if (handle == 0L) error("model is not loaded")
                        val b = msg.data
                        val out = native!!.generate(
                            handle,
                            b.getString("prompt").orEmpty(),
                            b.getInt("maxTokens", 96),
                            b.getFloat("temp", 0.4f),
                            b.getString("stops").orEmpty(),
                            /* grammar */ "",
                            null,
                        )
                        reply(replyTo, id, MSG_OK, out)
                    }
                    MSG_ABORT -> {
                        if (handle != 0L) native?.abort(handle)
                        reply(replyTo, id, MSG_OK, "aborted")
                    }
                    MSG_UNLOAD -> {
                        if (handle != 0L) {
                            native?.unload(handle)
                            handle = 0L
                        }
                        reply(replyTo, id, MSG_OK, "unloaded")
                    }
                    else -> reply(replyTo, id, MSG_ERR, "unknown message")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "llama worker", t)
                reply(replyTo, id, MSG_ERR, t.message ?: t.javaClass.simpleName)
            }
            true
        }
    }

    private fun ensureNative() {
        if (native != null) return
        native = LlamaNative()
        if (!LlamaNative.available) {
            error(LlamaNative.loadError ?: "libagentjin_llama.so failed to load")
        }
        native!!.initBackend()
    }

    private val gate = Handler(Looper.getMainLooper()) { msg ->
        val copy = Message.obtain()
        copy.copyFrom(msg)
        worker.sendMessage(copy)
        true
    }
    private val messenger = Messenger(gate)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        try {
            if (handle != 0L) native?.unload(handle)
        } catch (_: Throwable) {
        }
        handle = 0L
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgentJiN-llama-svc"
        const val MSG_PING = 1
        const val MSG_LOAD = 2
        const val MSG_GEN = 3
        const val MSG_ABORT = 4
        const val MSG_UNLOAD = 5
        const val MSG_OK = 10
        const val MSG_ERR = 11

        private fun reply(to: Messenger?, id: Int, what: Int, text: String) {
            if (to == null) return
            val m = Message.obtain(null, what)
            m.arg2 = id
            m.data = Bundle().apply { putString("text", text) }
            try {
                to.send(m)
            } catch (_: Exception) {
            }
        }
    }
}
