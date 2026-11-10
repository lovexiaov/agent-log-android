package cn.lovexiaov.agent.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Schema v1.4 AI 交互事件契约测试：六端共用的 6 个 `event_type`、snake_case
 * payload 词表、level 约定、`agent_auto=false`，以及「OTLP 不产 span」。
 */
class AiEventsTests {

    private fun sink(): MemorySink {
        val sink = MemorySink()
        AgentLog.bootstrap(LoggingConfig(service = "svc", minLevel = Level.DEBUG), sink)
        return sink
    }

    @Suppress("UNCHECKED_CAST")
    private fun line(sink: MemorySink, idx: Int): Map<String, Any?> = Json.parse(sink.lines()[idx]) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun payload(line: Map<String, Any?>): Map<String, Any?> = line["payload"] as Map<String, Any?>

    @Test
    fun requestUsesStandardEventTypeAndPayload() {
        val sink = sink()
        AgentLog.aiRequest("gpt-4o", "summarize this")
        val l = line(sink, 0)
        assertEquals("ai_request_initiated", l["event_type"])
        assertEquals("info", l["level"])
        assertEquals(false, l["agent_auto"])
        assertEquals("ai", l["category"])
        val p = payload(l)
        assertEquals("gpt-4o", p["model"])
        assertEquals("summarize this", p["prompt_text"])
        assertFalse(p.containsKey("promptText"))
    }

    @Test
    fun responseTokensAndRequestId() {
        val sink = sink()
        AgentLog.aiResponse("gpt-4o", 530, mapOf("request_id" to "r1", "prompt_tokens" to 320))
        val l = line(sink, 0)
        assertEquals("ai_response_received", l["event_type"])
        assertEquals("info", l["level"])
        val p = payload(l)
        assertEquals(530L, p["total_tokens"])
        assertEquals(320L, p["prompt_tokens"])
        assertEquals("r1", p["request_id"])
    }

    @Test
    fun toolCallAndResult() {
        val sink = sink()
        AgentLog.aiToolCall("search", mapOf("q" to "x"), mapOf("request_id" to "r2"))
        val initiated = line(sink, 0)
        assertEquals("ai_tool_call_initiated", initiated["event_type"])
        assertEquals("search", payload(initiated)["tool_name"])
        assertEquals("r2", payload(initiated)["request_id"])

        AgentLog.aiToolResult("search", true, mapOf("tool_call_latency_ms" to 12, "request_id" to "r2"))
        val completed = line(sink, 1)
        assertEquals("ai_tool_call_completed", completed["event_type"])
        assertEquals(true, payload(completed)["ok"])
        assertEquals("r2", payload(completed)["request_id"])
    }

    @Test
    fun guardrailIsWarn() {
        val sink = sink()
        AgentLog.aiGuardrail("toxicity", "blocked")
        val l = line(sink, 0)
        assertEquals("ai_guardrail_triggered", l["event_type"])
        assertEquals("warn", l["level"])
        assertEquals(false, l["agent_auto"])
        assertEquals("toxicity", payload(l)["guardrail_type"])
    }

    @Test
    fun errorUsesExceptionVocabulary() {
        val sink = sink()
        AgentLog.aiError("RateLimitError", "429 too many requests", mapOf("request_id" to "r9"))
        val l = line(sink, 0)
        assertEquals("ai_error", l["event_type"])
        assertEquals("error", l["level"])
        assertEquals(false, l["agent_auto"])
        val p = payload(l)
        assertEquals("RateLimitError", p["exception"])
        assertEquals("429 too many requests", p["exception_message"])
        assertEquals("handled", p["mechanism"])
    }

    @Test
    fun payloadHasNoCamelCaseAliases() {
        val sink = sink()
        AgentLog.aiResponse("gpt-4o", 10)
        val p = payload(line(sink, 0))
        assertFalse(p.containsKey("promptText"))
        assertFalse(p.containsKey("tokenUsage"))
        assertFalse(p.containsKey("totalTokens"))
        assertFalse(p.containsKey("requestId"))
    }

    @Test
    fun aiEventsNeverProduceSpans() {
        val aiTypes = listOf(
            EventType.AI_REQUEST_INITIATED,
            EventType.AI_RESPONSE_RECEIVED,
            EventType.AI_TOOL_CALL_INITIATED,
            EventType.AI_TOOL_CALL_COMPLETED,
            EventType.AI_GUARDRAIL_TRIGGERED,
            EventType.AI_ERROR,
        )
        for (type in aiTypes) {
            val event = LogEvent(
                ts = Instant.now().toString(),
                level = "info",
                service = "svc",
                eventType = type.raw,
                sessionId = TraceId.newTraceId(),
                agentAuto = false,
                message = "ai event",
                payload = mapOf("model" to JsonValue.Str("gpt-4o"), "total_tokens" to JsonValue.IntNum(530)),
            )
            assertNull(OtlpMapper.eventToSpan(event), "${type.raw} 不应产 span")
        }
    }
}
