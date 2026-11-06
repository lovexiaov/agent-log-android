package com.scaffold.agentlog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RedactorTests {
    @Test
    fun shouldMaskIgnoresCaseAndSeparators() {
        val r = Redactor()
        assertTrue(r.shouldMask("password"))
        assertTrue(r.shouldMask("PASSWORD"))
        assertTrue(r.shouldMask("api_key"))
        assertTrue(r.shouldMask("api-key"))
        assertTrue(r.shouldMask("accessToken"))
        assertTrue(r.shouldMask("ssn"))
        assertFalse(r.shouldMask("username"))
    }

    @Test
    fun shouldMaskSupportsPathLastSegment() {
        val r = Redactor(additional = setOf("user.email"))
        assertTrue(r.shouldMask("email")) // 命中 path 末段
        assertFalse(r.shouldMask("first_name"))
    }

    @Test
    fun redactsTextSecrets() {
        val r = Redactor()
        val out = r.redact("Authorization: Bearer abc123def token=xyz789")
        assertTrue(out.contains("***REDACTED***"))
        assertFalse(out.contains("abc123def"))
        assertFalse(out.contains("xyz789"))
    }

    @Test
    fun redactsNestedValuesRecursively() {
        val r = Redactor()
        // 顶层敏感键整体掩码；非敏感键的字符串值走正则脱敏（与 Swift maskSensitiveKeys 一致）。
        val payload = mapOf(
            "token" to "secret".toJsonValue(), // 顶层敏感键 -> 整体掩码
            "note" to "token=abc123".toJsonValue(), // 非敏感键，字符串值走正则
        )
        val out = r.maskSensitiveKeys(payload)
        assertEquals(JsonValue.Str("***REDACTED***"), out["token"])
        val note = (out["note"] as JsonValue.Str).value
        assertTrue(note.contains("***REDACTED***"))
        assertFalse(note.contains("abc123"))
    }
}
