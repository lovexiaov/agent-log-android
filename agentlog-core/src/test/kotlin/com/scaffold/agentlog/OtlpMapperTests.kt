package com.scaffold.agentlog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * OTLP 映射一致性：级别表、确定性 id、LogRecord 形状、HTTP/DB span 语义、
 * resource 分组与批次入口。
 *
 * 每条断言都对应 `docs/logging-framework/OTLP-MAPPING.md` 的一条规则；
 * Python / Go / Java / Web 的测试套件断言同一组不变量，保证六端可逐字比对。
 */
class OtlpMapperTests {
    private companion object {
        const val TRACE = "0123456789abcdef0123456789abcdef"
        const val SPAN = "a1b2c3d4e5f60718"
        const val TS = "2026-11-07T10:00:00.123456789Z"
        const val TS_NANOS = 1_794_045_600_123_456_789L
    }

    // ------------------------------------------------------------------ 工具

    private fun event(
        type: String = "log",
        level: String = "info",
        message: String = "hello",
        payload: Map<String, JsonValue>? = null,
        traceId: String? = null,
        spanId: String? = null,
        agentAuto: Boolean = false,
    ) = LogEvent(
        ts = TS,
        level = level,
        service = "demo-app",
        eventType = type,
        sessionId = "sess-1",
        traceId = traceId,
        spanId = spanId,
        agentAuto = agentAuto,
        message = message,
        env = "prod",
        payload = payload,
    )

    private fun fields(value: JsonValue): Map<String, JsonValue> {
        assertTrue(value is JsonValue.Obj, "expected an object, got $value")
        return (value as JsonValue.Obj).value
    }

    private fun values(value: JsonValue?): List<JsonValue> = (value as? JsonValue.Arr)?.value ?: emptyList()

    private fun text(value: JsonValue?): String? = (value as? JsonValue.Str)?.value

    private fun boolOf(value: JsonValue?): Boolean? = (value as? JsonValue.Bool)?.value

    private fun numberOf(value: JsonValue?): Long? = (value as? JsonValue.IntNum)?.value

    /** 在 attributes 数组里按键取 AnyValue。 */
    private fun attr(attributes: JsonValue?, key: String): JsonValue? =
        values(attributes).firstOrNull { text(fields(it)["key"]) == key }?.let { fields(it)["value"] }

    private fun stringAttr(attributes: JsonValue?, key: String): String? = text(
        attr(attributes, key)?.let {
            fields(it)["stringValue"]
        },
    )

    private fun firstRecord(logs: JsonValue): JsonValue =
        values(fields(values(fields(logs)["resourceLogs"]).first())["scopeLogs"]).let {
            values(fields(it.first())["logRecords"]).first()
        }

    // ------------------------------------------------------------------ 级别

    @Test
    fun severityTableMatchesSpec() {
        val expected = listOf(
            Triple("trace", "TRACE", 1),
            Triple("debug", "DEBUG", 5),
            Triple("info", "INFO", 9),
            Triple("warn", "WARN", 13),
            Triple("error", "ERROR", 17),
            Triple("critical", "FATAL", 21),
        )
        for ((level, text, number) in expected) {
            val severity = OtlpMapper.levelToSeverity(level)
            assertEquals(text, severity.text, "severityText for $level")
            assertEquals(number, severity.number, "severityNumber for $level")
        }
    }

    @Test
    fun warningAliasAndUnknownLevelDegradeToInfo() {
        assertEquals(OtlpMapper.Severity("WARN", 13), OtlpMapper.levelToSeverity("warning"))
        assertEquals(OtlpMapper.Severity("INFO", 9), OtlpMapper.levelToSeverity("nonsense"))
        assertEquals(OtlpMapper.Severity("INFO", 9), OtlpMapper.levelToSeverity(null))
    }

    // ------------------------------------------------------------------ 类型

    @Test
    fun anyValueFollowsProtoJsonMapping() {
        assertEquals(true, boolOf(fields(OtlpMapper.toAnyValue(JsonValue.Bool(true)))["boolValue"]))
        // int64 必须字符串化
        assertEquals("42", text(fields(OtlpMapper.toAnyValue(JsonValue.IntNum(42)))["intValue"]))
        assertEquals(
            0.5,
            (fields(OtlpMapper.toAnyValue(JsonValue.FloatNum(0.5)))["doubleValue"] as JsonValue.FloatNum).value,
        )
        assertEquals("hi", text(fields(OtlpMapper.toAnyValue(JsonValue.Str("hi")))["stringValue"]))
    }

    @Test
    fun nestedContainersStayStructuredAndNullsAreSkipped() {
        val array = fields(OtlpMapper.toAnyValue(JsonValue.Arr(listOf(JsonValue.IntNum(1), JsonValue.Str("two")))))
        val items = values(fields(array["arrayValue"] as JsonValue)["values"])
        assertEquals(2, items.size)
        assertEquals("1", text(fields(items[0])["intValue"]))

        val inner = mapOf("a" to JsonValue.IntNum(1), "skip" to JsonValue.Null)
        val kvlist = fields(OtlpMapper.toAnyValue(JsonValue.Obj(inner)))
        val kvs = values(fields(kvlist["kvlistValue"] as JsonValue)["values"])
        assertEquals(1, kvs.size, "null 值丢弃，不导出")
    }

    // ------------------------------------------------------------------ 时间

    @Test
    fun timestampParsesOffsetsAndTruncatesSubNanosecond() {
        val utc = OtlpMapper.parseTimestampNanos("2026-11-07T10:00:00Z")
        assertEquals(utc, OtlpMapper.parseTimestampNanos("2026-11-07T18:00:00+08:00"))
        assertEquals(utc, OtlpMapper.parseTimestampNanos("2026-11-07T18:00:00+0800"))
        assertEquals(utc, OtlpMapper.parseTimestampNanos("2026-11-07 10:00:00"))
        assertEquals(TS_NANOS, OtlpMapper.parseTimestampNanos(TS))
        assertEquals(
            TS_NANOS,
            OtlpMapper.parseTimestampNanos("2026-11-07T10:00:00.1234567899Z"),
            "亚纳秒位截断而非四舍五入",
        )
    }

    // --------------------------------------------------------------------- id

    @Test
    fun traceIdPassthroughAndDropOfNonHex() {
        assertEquals(TRACE, OtlpMapper.traceIdHex(event(traceId = TRACE.uppercase())))
        // Schema v1.2：非法值不派生、不变形，直接按无 trace 处理
        for (bad in listOf("opaque-trace", "0123456789abcdef0123456789abcde", 42)) {
            assertNull(OtlpMapper.traceIdHex(event(traceId = bad.toString())), bad.toString())
        }
        assertNull(OtlpMapper.traceIdHex(event()))
    }

    @Test
    fun spanIdPrefersExplicitValueAndIgnoresMalformedInput() {
        assertEquals(SPAN, OtlpMapper.spanIdHex(event(spanId = SPAN.uppercase())))
        val derived = OtlpMapper.spanIdHex(event())
        assertEquals(16, derived.length)
        assertEquals(derived, OtlpMapper.spanIdHex(event(spanId = "not-a-span-id")))
    }

    @Test
    fun spanWithoutTraceFallsBackToSessionTrace() {
        assertEquals(
            OtlpMapper.spanTraceId(event(type = "network_request")),
            OtlpMapper.spanTraceId(event(type = "db_query")),
            "同一会话的 span 归入同一条合成 trace",
        )
    }

    // --------------------------------------------------------------- LogRecord

    @Test
    fun logRecordCarriesSpecFields() {
        val record = OtlpMapper.eventToLogRecord(
            event(
                level = "error",
                message = "boom",
                payload = mapOf("attempt" to JsonValue.IntNum(2), "ok" to JsonValue.Bool(false)),
                traceId = TRACE,
                spanId = SPAN,
            ),
        )
        val f = fields(record)
        assertEquals("1794045600123456789", text(f["timeUnixNano"]))
        assertEquals(text(f["timeUnixNano"]), text(f["observedTimeUnixNano"]))
        assertEquals(17L, numberOf(f["severityNumber"]))
        assertEquals("ERROR", text(f["severityText"]))
        assertEquals("boom", text(fields(f["body"] as JsonValue)["stringValue"]))
        assertEquals(TRACE, text(f["traceId"]))
        assertEquals(SPAN, text(f["spanId"]))

        val attrs = f["attributes"] as JsonValue
        assertEquals("log", stringAttr(attrs, "agent.event.type"))
        assertEquals(false, boolOf(fields(attr(attrs, "agent.auto") as JsonValue)["boolValue"]))
        assertEquals("2", text(fields(attr(attrs, "payload.attempt") as JsonValue)["intValue"]))
        assertEquals(false, boolOf(fields(attr(attrs, "payload.ok") as JsonValue)["boolValue"]))
    }

    @Test
    fun logRecordWithoutTraceOmitsIds() {
        val f = fields(OtlpMapper.eventToLogRecord(event()))
        assertFalse(f.containsKey("traceId"))
        assertFalse(f.containsKey("spanId"))
    }

    @Test
    fun operationLogRecordSharesSpanIdWithItsSpan() {
        val e =
            event(
                type = "db_query",
                traceId = TRACE,
                spanId = SPAN,
                payload = mapOf("statement" to JsonValue.Str("SELECT 1")),
            )
        val record = fields(OtlpMapper.eventToLogRecord(e))
        val span = fields(OtlpMapper.eventToSpan(e)!!)
        assertEquals(SPAN, text(record["spanId"]))
        assertEquals(text(record["spanId"]), text(span["spanId"]))
    }

    @Test
    fun plainLogRecordNeverReferencesAMissingSpan() {
        val f = fields(OtlpMapper.eventToLogRecord(event(traceId = TRACE)))
        assertEquals(TRACE, text(f["traceId"]))
        assertFalse(f.containsKey("spanId"))
    }

    // -------------------------------------------------------------------- span

    @Test
    fun httpSpanUsesSemanticConventions() {
        val span = fields(
            OtlpMapper.eventToSpan(
                event(
                    type = "network_request",
                    traceId = TRACE,
                    payload = mapOf(
                        "method" to JsonValue.Str("GET"),
                        "url" to JsonValue.Str("https://api.example.com:8443/orders?page=1"),
                        "status_code" to JsonValue.IntNum(200),
                        "duration_ms" to JsonValue.IntNum(12),
                    ),
                ),
            )!!,
        )
        assertEquals("GET https://api.example.com:8443/orders?page=1", text(span["name"]))
        assertEquals(3L, numberOf(span["kind"]))
        assertEquals("1794045600123456789", text(span["startTimeUnixNano"]))
        assertEquals("1794045600135456789", text(span["endTimeUnixNano"]))
        assertFalse(span.containsKey("status"), "成功的 span 保持 status UNSET（不写 OK 噪音）")

        val attrs = span["attributes"] as JsonValue
        assertEquals("GET", stringAttr(attrs, "http.request.method"))
        assertEquals("200", text(fields(attr(attrs, "http.response.status_code") as JsonValue)["intValue"]))
        assertEquals("api.example.com", stringAttr(attrs, "server.address"))
        assertNull(attr(attrs, "error.type"))
    }

    @Test
    fun fractionalDurationKeepsSubMillisecondPrecision() {
        val span = fields(
            OtlpMapper.eventToSpan(
                event(type = "network_request", payload = mapOf("duration_ms" to JsonValue.FloatNum(42.5))),
            )!!,
        )
        val delta = text(span["endTimeUnixNano"])!!.toLong() - text(span["startTimeUnixNano"])!!.toLong()
        assertEquals(42_500_000L, delta)
    }

    @Test
    fun httpFailureSetsErrorStatus() {
        val span = fields(
            OtlpMapper.eventToSpan(
                event(
                    type = "network_request",
                    payload = mapOf(
                        "method" to JsonValue.Str("POST"),
                        "url" to JsonValue.Str("http://localhost:8080/pay"),
                        "status_code" to JsonValue.IntNum(503),
                    ),
                ),
            )!!,
        )
        assertEquals(2L, numberOf(fields(span["status"] as JsonValue)["code"]))
        assertEquals("503", stringAttr(span["attributes"] as JsonValue, "error.type"))
    }

    @Test
    fun dbSpanPrefersCanonicalStatementKey() {
        val span = fields(
            OtlpMapper.eventToSpan(
                event(
                    type = "db_query",
                    payload = mapOf(
                        "operation" to JsonValue.Str("select"),
                        "statement" to JsonValue.Str("SELECT 1"),
                        "sql" to JsonValue.Str("SELECT legacy"),
                        "db_system" to JsonValue.Str("postgresql"),
                    ),
                ),
            )!!,
        )
        assertEquals("select SELECT 1", text(span["name"]))
        val attrs = span["attributes"] as JsonValue
        assertEquals("SELECT 1", stringAttr(attrs, "db.statement"))
        assertEquals("postgresql", stringAttr(attrs, "db.system"))
    }

    @Test
    fun dbSpanAcceptsLegacySqlAndQueryKeysOnRead() {
        val legacySql = OtlpMapper.eventToSpan(
            event(
                type = "db_query",
                payload = mapOf("operation" to JsonValue.Str("exec"), "sql" to JsonValue.Str("INSERT 1")),
            ),
        )
        assertEquals("exec INSERT 1", text(fields(legacySql!!)["name"]))

        val legacyQuery = OtlpMapper.eventToSpan(
            event(
                type = "db_query",
                payload = mapOf("operation" to JsonValue.Str("exec"), "query" to JsonValue.Str("INSERT 2")),
            ),
        )
        assertEquals("exec INSERT 2", text(fields(legacyQuery!!)["name"]))
    }

    @Test
    fun nonOperationEventsProduceNoSpan() {
        for (type in listOf("log", "ui_interaction", "screen_view", "crash", "hang")) {
            assertNull(OtlpMapper.eventToSpan(event(type = type)), "$type 必须留在 logs 信号里")
        }
    }

    @Test
    fun crashCarriesExceptionAttributes() {
        val record = OtlpMapper.eventToLogRecord(
            event(
                type = "crash",
                agentAuto = true,
                payload = mapOf(
                    "exception" to JsonValue.Str("java.lang.IllegalStateException"),
                    "message" to JsonValue.Str("boom in billing"),
                    "stack" to JsonValue.Str("at Example.main(Example.java:1)"),
                ),
            ),
        )
        val attrs = fields(record)["attributes"] as JsonValue
        assertEquals("java.lang.IllegalStateException", stringAttr(attrs, "exception.type"))
        assertEquals("boom in billing", stringAttr(attrs, "exception.message"))
        assertEquals("at Example.main(Example.java:1)", stringAttr(attrs, "exception.stacktrace"))
        assertEquals(true, boolOf(fields(attr(attrs, "agent.auto") as JsonValue)["boolValue"]))
    }

    // ------------------------------------------------------------------- 批次

    @Test
    fun resourceGroupsByServiceEnvAndSession() {
        val second = LogEvent(
            ts = TS,
            level = "info",
            service = "demo-app",
            eventType = "log",
            sessionId = "sess-2",
            agentAuto = false,
            message = "c",
            env = "dev",
        )
        val body = OtlpMapper.toOtlpLogs(listOf(event(message = "a"), event(message = "b"), second))
        val groups = values(fields(body)["resourceLogs"])
        assertEquals(2, groups.size)

        val resource = fields(fields(groups[0])["resource"] as JsonValue)["attributes"] as JsonValue
        assertEquals("demo-app", stringAttr(resource, "service.name"))
        assertEquals("prod", stringAttr(resource, "deployment.environment"))
        assertEquals("sess-1", stringAttr(resource, "session.id"))
        assertEquals("agent-log", stringAttr(resource, "telemetry.sdk.name"))
        assertEquals("kotlin", stringAttr(resource, "telemetry.sdk.language"))
        assertEquals(OtlpMapper.SDK_VERSION, stringAttr(resource, "telemetry.sdk.version"))

        val scopeLogs = fields(values(fields(groups[0])["scopeLogs"]).first())
        assertEquals("agentlog.otlp", text(fields(scopeLogs["scope"] as JsonValue)["name"]))
        assertEquals(2, values(scopeLogs["logRecords"]).size, "组内保持输入顺序")
    }

    @Test
    fun extraResourceCannotOverrideBuiltins() {
        val body = OtlpMapper.toOtlpLogs(
            listOf(event()),
            mapOf("service.name" to JsonValue.Str("hijacked"), "service.version" to JsonValue.Str("2.1.0")),
        )
        val resource = fields(
            fields(values(fields(body)["resourceLogs"]).first())["resource"] as JsonValue,
        )["attributes"]
        assertEquals("demo-app", stringAttr(resource, "service.name"))
        assertEquals("2.1.0", stringAttr(resource, "service.version"))
        val names = values(resource).map { text(fields(it)["key"]) }.filter { it == "service.name" }
        assertEquals(1, names.size)
    }

    @Test
    fun tracesBodySkipsGroupsWithoutOperationEvents() {
        val op =
            event(
                type = "network_request",
                payload = mapOf("method" to JsonValue.Str("GET"), "url" to JsonValue.Str("http://a/b")),
            )
        val body = OtlpMapper.toOtlpSpans(listOf(event(), op))
        val groups = values(fields(body)["resourceSpans"])
        assertEquals(1, groups.size)
        val spans = values(fields(values(fields(groups[0])["scopeSpans"]).first())["spans"])
        assertEquals(1, spans.size, "普通日志不产 span")

        assertEquals(0, values(fields(OtlpMapper.toOtlpSpans(listOf(event())))["resourceSpans"]).size)
    }

    @Test
    fun toOtlpReturnsBothSignalsAndSerializes() {
        val both =
            fields(
                OtlpMapper.toOtlp(
                    listOf(event(type = "db_query", payload = mapOf("statement" to JsonValue.Str("SELECT 1")))),
                ),
            )
        assertNotNull(both["logs"])
        assertNotNull(both["traces"])

        val json = OtlpMapper.toOtlpJson(both["logs"] as JsonValue)
        assertFalse(json.contains(", "), "紧凑 JSON 无分隔空格")
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains("\"timeUnixNano\":\"1794045600123456789\""), "纳秒必须是字符串")
    }

    // -------------------------------------------------------------------- sink

    @Test
    fun sinkBuffersAndDrainsToOtlp() {
        val sink = OtlpMapper.OtlpSink()
        val logger = Logger(
            service = "demo-app",
            env = "dev",
            subsystem = "test",
            minLevel = Level.DEBUG,
            redactor = Redactor(),
            sink = sink,
        )
        logger.info("first")
        logger.recordAuto(
            EventType.NETWORK_REQUEST,
            "GET /x",
            "auto",
            null,
            mapOf("method" to "GET", "url" to "http://h/x"),
        )
        assertEquals(2, sink.size())

        val batch = fields(sink.drain())
        assertEquals(0, sink.size(), "drain 清空缓冲")
        assertEquals(
            2,
            values(fields(values(fields(batch["logs"] as JsonValue)["resourceLogs"]).first())["scopeLogs"])
                .let { values(fields(it.first())["logRecords"]).size },
        )
        assertEquals(
            1,
            values(fields(batch["traces"] as JsonValue)["resourceSpans"]).size,
            "只有 network_request 产 span",
        )
    }

    @Test
    fun firstRecordHelperMatchesBatchShape() {
        val logs = OtlpMapper.toOtlpLogs(listOf(event(message = "single")))
        assertEquals("single", text(fields(fields(firstRecord(logs))["body"] as JsonValue)["stringValue"]))
    }
}
