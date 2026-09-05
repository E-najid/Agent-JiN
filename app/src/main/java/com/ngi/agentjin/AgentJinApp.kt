package com.ngi.agentjin

import android.app.Application
import com.ngi.agentjin.core.di.AppContainer

class AgentJinApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
