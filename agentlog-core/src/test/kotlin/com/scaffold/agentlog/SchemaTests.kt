package com.scaffold.agentlog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 守住 Schema v1（真源：docs/logging-framework/schema/log-event.schema.json）。
 * 对齐 macOS `SchemaTests.swift`：只要线格式字段被无意改成 camelCase、或有人往顶层私加字段，本测试必须失败。
 */
class SchemaTests {
    private val requiredKeys = setOf("ts", "level", "service", "event_type", "session_id", "agent_auto", "message")
    private val optionalKeys = setOf("trace_id", "subsystem", "category", "env", "user_id", "payload")
    private val legacyKeys = setOf("eventType", "sessionId", "traceId", "agentAuto", "timestamp")

    private fun parseLine(line: String): Map<String, Any?> = Json.parse(line) as Map<String, Any?>

    private fun fullEntry(): LogEvent = LogEvent(
        ts = "2026-11-02T13:37:21.123Z",
        level = "info",
        service = "com.yourapp.agent",
        subsystem = "com.yourapp.agent",
        category = "auto",
        eventType = "network_request",
        sessionId = "s-1",
        traceId = "trace_abc",
        agentAuto = true,
        message = "GET https://api.example.com/orders -> 200",
        env = "prod",
        userId = "u-1",
        payload = mapOf("status" to 200.toJsonValue(), "duration_ms" to 128.toJsonValue()),
    )

    @Test
    fun wireKeysAreSubsetOfSchemaV1() {
        val obj = parseLine(fullEntry().toJsonLine())
        val allowed = requiredKeys + optionalKeys
        for (key in obj.keys) assertTrue(allowed.contains(key), "顶层出现 schema 外字段: $key")
        for (key in requiredKeys) assertNotNull(obj[key], "缺少必填字段 $key")
    }

    @Test
    fun camelCaseKeysNeverLeak() {
        val obj = parseLine(fullEntry().toJsonLine())
        for (key in legacyKeys) assertNull(obj[key], "线格式泄漏了内部命名 $key")
        assertEquals("network_request", obj["event_type"])
        assertEquals("s-1", obj["session_id"])
        assertEquals("trace_abc", obj["trace_id"])
        assertEquals(true, obj["agent_auto"])
    }

    @Test
    fun payloadStaysNested() {
        val obj = parseLine(fullEntry().toJsonLine())
        val payload = obj["payload"] as Map<String, Any?>
        assertEquals(200L, payload["status"])
        assertEquals(128L, payload["duration_ms"])
        assertNull(obj["status"], "业务字段不应提升到顶层")
        assertNull(obj["duration_ms"])
    }

    @Test
    fun levelVocabularyMatchesSchema() {
        val expected = setOf("trace", "debug", "info", "warn", "error", "critical")
        for (lvl in Level.entries) assertTrue(expected.contains(lvl.agentName), "非法 level: ${lvl.agentName}")
        assertEquals("warn", Level.WARN.agentName)
        assertEquals(Level.WARN, Level.fromWire("warning"))
        assertEquals(Level.WARN, Level.fromWire("warn"))
        assertNull(Level.fromWire("fatal"))
    }

    @Test
    fun decodeAcceptsSchemaLine() {
        val json = """{"ts":"2026-11-02T13:37:21.123Z","level":"info","service":"com.yourapp.agent",""" +
            """"subsystem":"com.yourapp.agent","category":"auto","event_type":"network_request",""" +
            """"session_id":"8f14e45f-ea0a-4c1b-9c3e-0d5b2c7a9e11","trace_id":"trace_3f9a1c2b7d4e",""" +
            """"agent_auto":true,"message":"GET https://api.example.com/orders -> 200 (128ms)",""" +
            """"env":"prod","payload":{"host":"api.example.com","path":"/orders",""" +
            """"method":"GET","status":200,"duration_ms":128}}"""
        val obj = parseLine(json)
        assertEquals("network_request", obj["event_type"])
        assertEquals("8f14e45f-ea0a-4c1b-9c3e-0d5b2c7a9e11", obj["session_id"])
        assertEquals(true, obj["agent_auto"])
        val payload = obj["payload"] as Map<String, Any?>
        assertEquals("GET", payload["method"])
        assertEquals(200L, payload["status"])
        assertEquals(128L, payload["duration_ms"])
    }
}
