package cn.lovexiaov.agent.log

import java.io.File

/**
 * 日志配置（语言中立，对齐 `config/logging.config.json` 与 Swift `AgentLogConfig`）。
 * 各语言实现解析同一份 JSON，保证「换语言不改配置」（INTERFACE.md §4）。
 */
data class LoggingConfig(
    val service: String,
    val env: String? = null,
    val release: String? = null,
    val subsystem: String? = null,
    val minLevel: Level = Level.DEBUG,
    val redactEnabled: Boolean = true,
    /** 追加到默认脱敏键（不可减，C6）。 */
    val redactPaths: List<String> = emptyList(),
    val autoInstrumentation: Set<AutoInstrumentation> = AutoInstrumentation.ALL,
    /** JSONL 落盘目录；Android 端通常为 `context.cacheDir/agentlog`。 */
    val exportDirectory: File? = null,
) {
    companion object {
        /**
         * 从与 Web/macOS 共用的 `config/logging.config.json` 解析（语言中立，换语言不改配置）。
         * 仅读取本端需要的子集（format/level/service/env/redactPaths）；sentry/browser 等忽略。
         */
        fun fromJson(json: String): LoggingConfig {
            val root = Json.parse(json) as? Map<*, *> ?: emptyMap<String, Any?>()
            val service = root["service"] as? String ?: "app"
            val env = root["env"] as? String
            val release = root["release"] as? String
            val levelName = root["level"] as? String
            val minLevel = Level.fromName(levelName ?: "info") ?: Level.INFO
            val redactPaths = (root["redactPaths"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            return LoggingConfig(
                service = service,
                env = env,
                release = release,
                minLevel = minLevel,
                redactPaths = redactPaths,
            )
        }
    }
}
