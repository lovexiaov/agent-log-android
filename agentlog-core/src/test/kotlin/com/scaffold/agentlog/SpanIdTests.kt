package com.scaffold.agentlog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Schema v1.1 span_id：绑定作用域传播、大小写归一，以及"非法值丢弃而不写出"。
 * 与 Java `SpanIdTests`、Go `span_test.go`、Python `test_schema.py` 保持同一套断言口径。
 */
class SpanIdTests {
    private companion object {
        const val TRACE = "0123456789abcdef0123456789abcdef"
        const val SPAN = "a1b2c3d4e5f60718"
    }

    private fun logger(): Pair<Logger, MemorySink> {
        val sink = MemorySink()
        val logger = Logger(
            service = "svc",
            env = "dev",
            subsystem = "test.agent",
            minLevel = Level.DEBUG,
            redactor = Redactor(),
            sink = sink,
        )
        return logger to sink
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(line: String): Map<String, Any?> = Json.parse(line) as Map<String, Any?>

    @Test
    fun normalizeAcceptsOnly16HexChars() {
        assertEquals(SPAN, SpanId.normalize("A1B2C3D4E5F60718"))
        assertEquals(SPAN, SpanId.normalize("  a1b2c3d4e5f60718 "))
        assertNull(SpanId.normalize(null))
        assertNull(SpanId.normalize("a1b2c3d4e5f6071"))
        assertNull(SpanId.normalize("a1b2c3d4e5f607189"))
        assertNull(SpanId.normalize("g1b2c3d4e5f60718"))
        assertNull(SpanId.normalize(""))
    }

    @Test
    fun childBoundSpanIdIsWrittenLowercase() {
        val (logger, sink) = logger()
        logger.child(mapOf("traceId" to TRACE, "spanId" to "A1B2C3D4E5F60718"))
            .info("inside span")

        val event = parse(sink.lines().first())
        assertEquals(SPAN, event["span_id"])
        assertEquals(TRACE, event["trace_id"])
    }

    @Test
    fun childAcceptsSnakeCaseSpanId() {
        val (logger, sink) = logger()
        logger.child(mapOf("trace_id" to TRACE, "span_id" to SPAN)).info("snake case")

        val event = parse(sink.lines().first())
        assertEquals(SPAN, event["span_id"])
        assertEquals(TRACE, event["trace_id"])
    }

    @Test
    fun withSpanConvenienceBindsBothIds() {
        val (logger, sink) = logger()
        logger.withSpan(TRACE, SPAN).info("via withSpan")

        val event = parse(sink.lines().first())
        assertEquals(SPAN, event["span_id"])
        assertEquals(TRACE, event["trace_id"])
    }

    @Test
    fun parentDoesNotInheritChildSpan() {
        val (logger, sink) = logger()
        logger.withSpan(TRACE, SPAN).info("inside")
        logger.info("outside")

        val inside = parse(sink.lines()[0])
        val outside = parse(sink.lines()[1])
        assertEquals(SPAN, inside["span_id"])
        assertFalse(outside.containsKey("span_id"))
        assertFalse(outside.containsKey("trace_id"))
    }

    @Test
    fun invalidSpanIdIsDropped() {
        val (logger, sink) = logger()
        for (bad in listOf("not-a-span", "a1b2c3d4e5f6071", "zzzzzzzzzzzzzzzz")) {
            logger.withSpan(TRACE, bad).info("bad span")
        }
        for (line in sink.lines()) {
            val event = parse(line)
            assertFalse(event.containsKey("span_id"), "非法 span_id 不应写出")
            assertEquals(TRACE, event["trace_id"])
        }
    }

    @Test
    fun spanIdNeverLeaksIntoPayload() {
        val (logger, sink) = logger()
        logger.child(mapOf("spanId" to SPAN)).info("x")

        val event = parse(sink.lines().first())
        val payload = event["payload"] as? Map<*, *>
        assertFalse(payload?.containsKey("spanId") == true)
        assertFalse(payload?.containsKey("span_id") == true)
    }

    @Test
    fun autoEventsCarryTheBoundSpan() {
        val (logger, sink) = logger()
        val span = logger.withSpan(TRACE, SPAN)
        span.recordAuto(EventType.NETWORK_REQUEST, "GET https://api.example.com/x -> 200")

        val event = parse(sink.lines().first())
        assertEquals(SPAN, event["span_id"])
        assertEquals("network_request", event["event_type"])
        assertEquals(true, event["agent_auto"])
    }
}
