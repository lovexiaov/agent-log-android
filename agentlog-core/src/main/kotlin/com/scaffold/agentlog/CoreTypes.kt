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
 * Schema v1.1 span id：16 位小写 hex，与 W3C traceparent 的 span-id 同构。
 *
 * 非法值一律返回 null（调用方丢弃而不是写出），保证坏值永远不会产出违规行。
 */
object SpanId {
    private val PATTERN = Regex("^[0-9a-fA-F]{16}$")

    fun normalize(raw: String?): String? {
        val value = raw?.trim() ?: return null
        return if (PATTERN.matches(value)) value.lowercase() else null
    }
}

/**
 * 自动埋点事件类型。与 Web 端（`agent-log-web`）的 `event_type` 概念对齐。
 *
 * `db_query` 与 Java/Go/Python 端同名（Schema v1.1 起 payload 词表统一，
 * SQL 文本一律写 `statement`，见 README §3.5）；库本身不做 SQL 埋点，
 * 这里保留该类型是为了让业务侧/上层埋点能产出符合词表的事件。
 */
enum class EventType(val raw: String) {
    SCREEN_VIEW("screen_view"),
    UI_INTERACTION("ui_interaction"),
    NETWORK_REQUEST("network_request"),
    CRASH("crash"),
    HANG("hang"),
    DB_QUERY("db_query"),
    LOG("log"),
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
    HANG,
    ;

    companion object {
        val ALL: Set<AutoInstrumentation> = entries.toSet()
    }
}
