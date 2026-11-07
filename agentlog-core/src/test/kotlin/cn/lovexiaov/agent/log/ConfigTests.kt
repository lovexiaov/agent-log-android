package cn.lovexiaov.agent.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigTests {
    @Test
    fun parsesSharedConfig() {
        val json = """{
          "format":"json","level":"info","service":"ai-dev-spec","env":"production",
          "redactPaths":["password","token","authorization","secret","apiKey","user.email","user.phone"],
          "sentry":{"enabled":false},"browser":{"enabled":false,"sampleRate":0.1}
        }"""
        val cfg = LoggingConfig.fromJson(json)
        assertEquals("ai-dev-spec", cfg.service)
        assertEquals(Level.INFO, cfg.minLevel)
        assertEquals("production", cfg.env)
        assertTrue(cfg.redactPaths.contains("user.email"))

        // 默认脱敏键不可减（与 Swift/Web 一致）：合并后至少包含 password/token
        val r = Redactor(additional = cfg.redactPaths.toSet())
        assertTrue(r.shouldMask("password"))
        assertTrue(r.shouldMask("token"))
        assertTrue(r.shouldMask("email")) // path 末段匹配 user.email
    }

    @Test
    fun defaultsWhenFieldsMissing() {
        val cfg = LoggingConfig.fromJson("""{"service":"x"}""")
        assertEquals("x", cfg.service)
        assertEquals(Level.INFO, cfg.minLevel) // 缺省 info
        assertNull(cfg.env)
    }
}
