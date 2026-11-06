package com.scaffold.agentlog

/**
 * 面向 Agent 的统一日志条目 —— Schema v1 线格式（snake_case）。
 *
 * 真源：[docs/logging-framework/schema/log-event.schema.json](../../../../../docs/logging-framework/schema/log-event.schema.json)。
 * Kotlin 内部用 camelCase 属性（`eventType`/`sessionId`/`traceId`/`agentAuto`），
 * **仅在 [toJsonLine] 序列化边界**映射为 snake_case；顶层字段集封闭，业务字段一律进 `payload`。
 */
data class LogEvent(
    val ts: String,
    val level: String,
    val service: String,
    val subsystem: String? = null,
    val category: String? = null,
    val eventType: String,
    val sessionId: String,
    val traceId: String? = null,
    val agentAuto: Boolean,
    val message: String,
    val env: String? = null,
    val userId: String? = null,
    val payload: Map<String, JsonValue>? = null,
) {
    /** 单行 JSON（JSONL），Agent 可直接按行读取。 */
    fun toJsonLine(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"ts\":").append(Json.quote(ts)).append(',')
        sb.append("\"level\":").append(Json.quote(level)).append(',')
        sb.append("\"service\":").append(Json.quote(service)).append(',')
        sb.append("\"event_type\":").append(Json.quote(eventType)).append(',')
        sb.append("\"session_id\":").append(Json.quote(sessionId)).append(',')
        sb.append("\"agent_auto\":").append(if (agentAuto) "true" else "false").append(',')
        sb.append("\"message\":").append(Json.quote(message))
        subsystem?.let { sb.append(",\"subsystem\":").append(Json.quote(it)) }
        category?.let { sb.append(",\"category\":").append(Json.quote(it)) }
        env?.let { sb.append(",\"env\":").append(Json.quote(it)) }
        userId?.let { sb.append(",\"user_id\":").append(Json.quote(it)) }
        traceId?.let { sb.append(",\"trace_id\":").append(Json.quote(it)) }
        payload?.let { p ->
            if (p.isNotEmpty()) {
                sb.append(",\"payload\":")
                Json.writeObj(sb, p)
            }
        }
        sb.append('}')
        return sb.toString()
    }
}
