package com.scaffold.agentlog.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.scaffold.agentlog.AgentLog
import com.scaffold.agentlog.AutoInstrumentation
import com.scaffold.agentlog.EventType
import com.scaffold.agentlog.Level
import com.scaffold.agentlog.LoggingConfig
import java.io.File

/**
 * Android 平台胶水：在 `AgentLog` 核心之上安装平台专属自动埋点（对齐 macOS `AgentLog.installAuto`）。
 * 业务在 `Application.onCreate` 最早处调用 [bootstrap]，即获得默认全开的自动埋点。
 *
 * 自动埋点范围（默认全开，可逐项关闭）：
 * - [AutoInstrumentation.SCREEN_VIEW]：Activity 进入前台 → `screen_view`
 * - [AutoInstrumentation.CRASH]：未捕获异常 → `crash`
 * - [AutoInstrumentation.HANG]：主线程卡顿（>250ms）→ `hang`
 * - [AutoInstrumentation.NETWORK]：由业务把 [AgentNetworkInterceptor] 加入 OkHttpClient 实现（见 README）
 */
object AgentLogAndroid {

    fun bootstrap(context: Context, config: LoggingConfig) {
        // JSONL 落盘目录：默认 App 私有 cache 目录，避免污染外部存储。
        val dir = config.exportDirectory ?: File(context.cacheDir, "agentlog")
        AgentLog.bootstrap(config.copy(exportDirectory = dir))

        if (config.autoInstrumentation.contains(AutoInstrumentation.CRASH)) installCrashHandler()
        if (config.autoInstrumentation.contains(AutoInstrumentation.HANG)) HangMonitor.start()
        if (config.autoInstrumentation.contains(AutoInstrumentation.SCREEN_VIEW) && context is Application) {
            context.registerActivityLifecycleCallbacks(ScreenViewCallback())
        }
        // NETWORK 由业务把 AgentNetworkInterceptor 加入 OkHttpClient 完成（见 README）。
    }

    /** 便捷入口：仅传 subsystem（= service），env 默认 prod，业务应显式覆盖。 */
    fun bootstrap(context: Context, subsystem: String, env: String = "prod", minLevel: Level = Level.DEBUG) {
        bootstrap(
            context,
            LoggingConfig(service = subsystem, subsystem = subsystem, env = env, minLevel = minLevel),
        )
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AgentLog.recordAuto(
                EventType.CRASH,
                "uncaught: ${throwable.message ?: throwable.javaClass.simpleName}",
                payload = mapOf(
                    "thread" to thread.name,
                    "exception" to throwable.javaClass.name,
                    "stack" to throwable.stackTraceToString().take(4000),
                ),
            )
            previous?.uncaughtException(thread, throwable)
        }
    }
}

/**
 * 页面浏览自动埋点：Activity 进入前台（onResumed）记录 `screen_view`。
 * 对齐 macOS 的 `swizzle NSViewController.viewDidAppear`。
 */
class ScreenViewCallback : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        val name = activity.javaClass.simpleName
        AgentLog.recordAuto(
            EventType.SCREEN_VIEW,
            "screen_view $name",
            payload = mapOf("activity" to name, "package" to activity.packageName),
        )
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
