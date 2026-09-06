package com.ngi.agentjin

import android.app.Application
import com.ngi.agentjin.core.di.AppContainer
import java.io.File

class AgentJinApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (isLlamaProcess()) return
        container = try {
            AppContainer(this)
        } catch (t: Throwable) {
            throw RuntimeException("Agent JiN failed to start: ${t.message}", t)
        }
    }

    private fun isLlamaProcess(): Boolean {
        return try {
            File("/proc/self/cmdline").readText().contains(":llama")
        } catch (_: Exception) {
            false
        }
    }
}
