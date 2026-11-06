package com.scaffold.agentlog.example

import android.app.Application
import com.scaffold.agentlog.Level
import com.scaffold.agentlog.LoggingConfig
import com.scaffold.agentlog.android.AgentLogAndroid

/**
 * 演示接入：在 Application.onCreate 最早处一处 [AgentLogAndroid.bootstrap]，
 * 即获得默认全开的自动埋点（screen_view / crash / hang / network）。
 */
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentLogAndroid.bootstrap(
            context = this,
            config =
            LoggingConfig(
                service = "com.scaffold.agentlog.example",
                subsystem = "com.scaffold.agentlog.example.agent",
                env = "dev",
                minLevel = Level.INFO,
            ),
        )
    }
}
