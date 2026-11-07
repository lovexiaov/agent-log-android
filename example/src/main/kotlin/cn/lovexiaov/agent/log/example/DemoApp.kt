package cn.lovexiaov.agent.log.example

import android.app.Application
import cn.lovexiaov.agent.log.Level
import cn.lovexiaov.agent.log.LoggingConfig
import cn.lovexiaov.agent.log.android.AgentLogAndroid

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
                service = "cn.lovexiaov.agent.log.example",
                subsystem = "cn.lovexiaov.agent.log.example.agent",
                env = "dev",
                minLevel = Level.INFO,
            ),
        )
    }
}
