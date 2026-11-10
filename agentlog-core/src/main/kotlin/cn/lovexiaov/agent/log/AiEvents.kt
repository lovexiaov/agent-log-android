package cn.lovexiaov.agent.log

/**
 * AI 交互事件便捷方法（Schema v1.4）。
 *
 * 每个方法写出 6 个标准 AI [EventType] 之一，payload 只用 INTERFACE.md §4.4 的
 * snake_case 词表；字段缺省即不写，不强制。调用方可用 [extra] 追加业务键
 * （如 `request_id` / `provider` / token 明细），但标准键以方法参数为准。
 *
 * 这些是**业务显式埋点**（`agent_auto=false`）：框架只准备数据，何时/何处记录
 * 一次 LLM 调用由调用方决定。AI 事件在 OTLP 层只作为 log 信号，不产 span。
 */
object AiEvents {

    private fun payload(extra: Map<String, Any?>?): MutableMap<String, Any?> =
        LinkedHashMap<String, Any?>().apply { extra?.let { putAll(it) } }

    private fun record(level: Level, eventType: EventType, message: String, body: Map<String, Any?>) {
        AgentLog.logger().record(level, eventType.raw, message, category = "ai", payload = body)
    }

    /** 发出 `ai_request_initiated`：一次 LLM 调用的请求发起。 */
    fun aiRequest(model: String, promptText: String, extra: Map<String, Any?>? = null) {
        val p = payload(extra)
        p["model"] = model
        p["prompt_text"] = promptText
        record(Level.INFO, EventType.AI_REQUEST_INITIATED, "ai request initiated", p)
    }

    /** 发出 `ai_response_received`：收到模型响应，带 token 用量。 */
    fun aiResponse(model: String, totalTokens: Int, extra: Map<String, Any?>? = null) {
        val p = payload(extra)
        p["model"] = model
        p["total_tokens"] = totalTokens
        record(Level.INFO, EventType.AI_RESPONSE_RECEIVED, "ai response received", p)
    }

    /** 发出 `ai_tool_call_initiated`：模型发起一次工具调用。 */
    fun aiToolCall(toolName: String, toolArguments: Any?, extra: Map<String, Any?>? = null) {
        val p = payload(extra)
        p["tool_name"] = toolName
        p["tool_arguments"] = toolArguments
        record(Level.INFO, EventType.AI_TOOL_CALL_INITIATED, "ai tool call initiated", p)
    }

    /** 发出 `ai_tool_call_completed`：工具调用完成。 */
    fun aiToolResult(toolName: String, ok: Boolean, extra: Map<String, Any?>? = null) {
        val p = payload(extra)
        p["tool_name"] = toolName
        p["ok"] = ok
        record(Level.INFO, EventType.AI_TOOL_CALL_COMPLETED, "ai tool call completed", p)
    }

    /** 发出 `ai_guardrail_triggered`（warn）：护栏 / 合规拦截触发。 */
    fun aiGuardrail(guardrailType: String, reason: String, extra: Map<String, Any?>? = null) {
        val p = payload(extra)
        p["guardrail_type"] = guardrailType
        p["reason"] = reason
        record(Level.WARN, EventType.AI_GUARDRAIL_TRIGGERED, "ai guardrail triggered", p)
    }

    /** 发出 `ai_error`（error），复用 v1.3 异常词表。 */
    fun aiError(errorType: String, message: String, extra: Map<String, Any?>? = null) {
        val p = payload(extra)
        p["exception"] = errorType
        p["exception_message"] = message
        p["mechanism"] = "handled"
        record(Level.ERROR, EventType.AI_ERROR, "ai interaction error", p)
    }
}
