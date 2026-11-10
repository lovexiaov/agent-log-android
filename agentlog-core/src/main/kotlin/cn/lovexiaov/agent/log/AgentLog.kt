package cn.lovexiaov.agent.log

import java.io.File

/**
 * 业务入口（薄封装，对齐 Swift `AgentLog` / Web `createLogger`）。
 *
 * 一处 `bootstrap` 初始化共享 [Logger]；业务侧用 [info]/[warn]/[error] 等替代裸 `Log.*`/`println`；
 * 自动埋点由平台胶水层（[cn.lovexiaov.agent.log.android.AgentLogAndroid]）调用 [recordAuto]。
 *
 * 注意：本对象是 JVM 测试/通用门面；Android 平台请用 `AgentLogAndroid.bootstrap(context, config)`，
 * 它内部调用本 `bootstrap` 后再安装 Android 专属钩子。
 */
object AgentLog {
    private lateinit var shared: Logger
    private lateinit var sinkFile: File

    fun bootstrap(config: LoggingConfig) {
        bootstrap(config, FileSink(logFile(config)))
    }

    /** 指定 sink 初始化（测试用，对齐 Java `AgentLog.bootstrap(config, sink)`）。 */
    fun bootstrap(config: LoggingConfig, sink: LogSink) {
        sinkFile = logFile(config)
        shared = Logger(
            service = config.service,
            env = config.env,
            subsystem = config.subsystem,
            minLevel = config.minLevel,
            redactor = Redactor(additional = config.redactPaths.toSet()),
            sink = sink,
            redactEnabled = config.redactEnabled,
            release = config.release,
        )
    }

    private fun logFile(config: LoggingConfig): File {
        val dir = config.exportDirectory ?: File(System.getProperty("java.io.tmpdir"), "agent-log")
        return File(dir, "agent-log.jsonl")
    }

    /** 共享 [Logger]；未 bootstrap 时给出明确错误（对齐 Java）。 */
    fun logger(): Logger {
        if (!::shared.isInitialized) {
            error("AgentLog not bootstrapped. Call AgentLog.bootstrap(config) first.")
        }
        return shared
    }

    fun info(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        shared.info(message, category, traceId, payload)

    fun debug(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        shared.debug(message, category, traceId, payload)

    fun trace(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        shared.trace(message, category, traceId, payload)

    fun warn(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        shared.warn(message, category, traceId, payload)

    fun error(message: String, category: String = "app", traceId: String? = null, payload: Map<String, Any?>? = null) =
        shared.error(message, category, traceId, payload)

    fun critical(
        message: String,
        category: String = "app",
        traceId: String? = null,
        payload: Map<String, Any?>? = null,
    ) = shared.critical(message, category, traceId, payload)

    fun child(ctx: Map<String, Any?>): Logger = shared.child(ctx)
    fun setLevel(level: Level) = shared.setLevel(level)
    fun redact(paths: List<String>) = shared.redact(paths)
    fun emit(event: LogEvent) = shared.emit(event)
    fun recordAuto(
        eventType: EventType,
        message: String,
        category: String = "auto",
        traceId: String? = null,
        payload: Map<String, Any?>? = null,
    ) = shared.recordAuto(eventType, message, category, traceId, payload)

    // ---- AI 交互事件（Schema v1.4，业务显式埋点；详见 AiEvents） ----
    fun aiRequest(model: String, promptText: String, extra: Map<String, Any?>? = null) =
        AiEvents.aiRequest(model, promptText, extra)

    fun aiResponse(model: String, totalTokens: Int, extra: Map<String, Any?>? = null) =
        AiEvents.aiResponse(model, totalTokens, extra)

    fun aiToolCall(toolName: String, toolArguments: Any?, extra: Map<String, Any?>? = null) =
        AiEvents.aiToolCall(toolName, toolArguments, extra)

    fun aiToolResult(toolName: String, ok: Boolean, extra: Map<String, Any?>? = null) =
        AiEvents.aiToolResult(toolName, ok, extra)

    fun aiGuardrail(guardrailType: String, reason: String, extra: Map<String, Any?>? = null) =
        AiEvents.aiGuardrail(guardrailType, reason, extra)

    fun aiError(errorType: String, message: String, extra: Map<String, Any?>? = null) =
        AiEvents.aiError(errorType, message, extra)

    /** 导出当前 JSONL 副本/路径，供 Agent 抓取或人工分析。 */
    fun export(): File = sinkFile
    fun exportPath(): String? = shared.exportPath()
}
