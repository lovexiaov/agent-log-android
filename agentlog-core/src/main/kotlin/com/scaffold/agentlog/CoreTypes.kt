package com.scaffold.agentlog

import java.util.UUID

/**
 * 一次会话（= 一次进程/App 启动）的稳定标识，用于把同一次运行的日志聚合。
 * 与 macOS 端 `Session.id`、Web 端 `PROCESS_SESSION_ID` 语义一致。
 */
object Session {
    val id: String = UUID.randomUUID().toString()
}

/**
 * 自动埋点事件类型。与 Web 端（`agent-log-web`）的 `event_type` 概念对齐。
 */
enum class EventType(val raw: String) {
    SCREEN_VIEW("screen_view"),
    UI_INTERACTION("ui_interaction"),
    NETWORK_REQUEST("network_request"),
    CRASH("crash"),
    HANG("hang"),
    LOG("log");
}

/**
 * 自动埋点开关（对齐 Swift `AutoInstrumentationOptions`）。
 * 业务可按需关闭某类（如发布版本关 UI 埋点）。
 */
enum class AutoInstrumentation {
    SCREEN_VIEW,
    UI_INTERACTION,
    NETWORK,
    CRASH,
    HANG;

    companion object {
        val ALL: Set<AutoInstrumentation> = entries.toSet()
    }
}
