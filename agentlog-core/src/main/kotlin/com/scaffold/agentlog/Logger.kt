package com.scaffold.agentlog

import java.io.File
import java.time.Instant

/** 日志写出目标。 */
interface LogSink {
    fun write(line: String)
}

/**
 * 线程安全的 JSONL 文件 sink（面向 Agent 的主消费源，对齐 Swift `FileSink`）。
 * 与 swift-log 的格式化解耦，Agent 可按行可靠读取。
 */
class FileSink(private val file: File) : LogSink {
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
    }

    val path: String get() = file.absolutePath

    override fun write(line: String) {
        synchronized(lock) { file.appendText(line + "\n") }
    }
}

/** 内存 sink（测试用，便于断言而不落盘）。 */
class MemorySink : LogSink {
    private val lines = mutableListOf<String>()
    private val lock = Any()

    fun lines(): List<String> = synchronized(lock) { lines.toList() }
    override fun write(line: String) {
        synchronized(lock) { lines.add(line) }
    }
}

/**
 * 核心 Logger：实现 `INTERFACE.md §1` 四个必实现方法（[emit]/[child]/[setLevel]/[redact]）。
 * 纯 Kotlin/JVM，无 Android 依赖，可在无 SDK 环境编译与测试。
 *
 * 设计对齐 Web (`agent-log-web`) 与 macOS (`AgentLog`)：
 * - 保留键（userId/sessionId/traceId/subsystem/env/category/event）提升为顶层，其余进 `payload`；
 * - 自动埋点事件（`agentAuto=true`）始终记录；业务手动日志遵守 `minLevel`；
 * - 脱敏在 logger 层拦截（[Redactor]），不依赖调用方自觉。
 */
class Logger(
    private val service: String,
    private val env: String?,
    private val subsystem: String?,
    minLevel: Level,
    private var redactor: Redactor,
    private val sink: LogSink,
    private val sessionId: String = Session.id,
    private val redactEnabled: Boolean = true,
    private val bound: Map<String, Any?> = emptyMap(),
) {
    var minLevel: Level = minLevel
        private set

    /** 绑定上下文（traceId/userId/subsystem/env 等），返回新实例，不污染原实例（对齐 TS child）。 */
    fun child(ctx: Map<String, Any?>): Logger = Logger(
        service = service, env = env, subsystem = subsystem, minLevel = minLevel,
        redactor = redactor, sink = sink, sessionId = sessionId,
        redactEnabled = redactEnabled, bound = bound + ctx,
    )

    /** 设置最低输出级别（INTERFACE §1）。 */
    fun setLevel(level: Level) {
        minLevel = level
    }

    /** 追加脱敏路径（默认键不可关闭，只能追加，C6）。 */
    fun redact(paths: List<String>) {
        redactor = redactor.withAdditional(paths)
    }

    /**
     * 接收结构化事件 → 映射为 Schema v1（已由 [LogEvent] 在 toJsonLine 完成 snake_case）→ 脱敏 → 写出。
     * 自动埋点事件（[LogEvent.agentAuto]=true）始终记录；手动日志遵守 [minLevel]。
     */
    fun emit(event: LogEvent) {
        if (!event.agentAuto && (Level.fromName(event.level)?.weight ?: 0) < minLevel.weight) return
        val clean = if (redactEnabled) {
            event.copy(
                message = redactor.redact(event.message),
                payload = event.payload?.let { redactor.maskSensitiveKeys(it) },
            )
        } else {
            event
        }
        sink.write(clean.toJsonLine())
    }

    // ---- 业务手动日志（agentAuto = false） ----
    fun trace(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        log(Level.TRACE, message, category, traceId, EventType.LOG, payload, agentAuto = false)

    fun debug(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        log(Level.DEBUG, message, category, traceId, EventType.LOG, payload, agentAuto = false)

    fun info(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        log(Level.INFO, message, category, traceId, EventType.LOG, payload, agentAuto = false)

    fun warn(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        log(Level.WARN, message, category, traceId, EventType.LOG, payload, agentAuto = false)

    fun error(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        log(Level.ERROR, message, category, traceId, EventType.LOG, payload, agentAuto = false)

    fun critical(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        log(Level.CRITICAL, message, category, traceId, EventType.LOG, payload, agentAuto = false)

    // ---- 自动埋点内部回调（agentAuto = true，框架核心价值，始终记录） ----
    fun recordAuto(
        eventType: EventType,
        message: String,
        category: String = "auto",
        traceId: String? = null,
        payload: Map<String, Any?>? = null,
    ) = log(Level.INFO, message, category, traceId, eventType, payload, agentAuto = true)

    private fun log(
        level: Level,
        message: String,
        category: String?,
        traceId: String?,
        eventType: EventType,
        payload: Map<String, Any?>?,
        agentAuto: Boolean,
    ) {
        val merged = bound.toMutableMap()
        if (category != null) merged["category"] = category
        if (traceId != null) merged["traceId"] = traceId
        if (payload != null) merged += payload

        val reserved = setOf("userId", "sessionId", "traceId", "event", "category", "subsystem", "env")
        val payloadMap = merged.filterKeys { it !in reserved }.mapValues { it.value.toJsonValue() }

        val event = LogEvent(
            ts = Instant.now().toString(),
            level = level.agentName,
            service = service,
            subsystem = (merged["subsystem"] as? String) ?: subsystem,
            category = (merged["category"] as? String),
            eventType = (merged["event"] as? String) ?: eventType.raw,
            sessionId = (merged["sessionId"] as? String) ?: sessionId,
            traceId = (merged["traceId"] as? String),
            agentAuto = agentAuto,
            message = message,
            env = (merged["env"] as? String) ?: env,
            userId = (merged["userId"] as? String),
            payload = if (payloadMap.isEmpty()) null else payloadMap,
        )
        emit(event)
    }

    /** 程序化读取全部结构化条目（Agent 消费入口）。 */
    fun exportPath(): String? = (sink as? FileSink)?.path
}
