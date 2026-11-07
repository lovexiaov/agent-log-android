package cn.lovexiaov.agent.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoggerTests {
    private fun logger(minLevel: Level = Level.DEBUG, redactEnabled: Boolean = true): Pair<Logger, MemorySink> {
        val sink = MemorySink()
        val logger = Logger(
            service = "test.service",
            env = "dev",
            subsystem = "test.agent",
            minLevel = minLevel,
            redactor = Redactor(),
            sink = sink,
            redactEnabled = redactEnabled,
        )
        return logger to sink
    }

    private fun parse(line: String): Map<String, Any?> = Json.parse(line) as Map<String, Any?>

    @Test
    fun childMergesContextAndDoesNotMutateParent() {
        val sink = MemorySink()
        val parent = Logger(
            service = "s",
            env = "dev",
            subsystem = "sub",
            minLevel = Level.DEBUG,
            redactor = Redactor(),
            sink = sink,
            redactEnabled = true,
        )
        val child = parent.child(mapOf("userId" to "u-42"))
        child.info("from child")
        parent.info("from parent")
        val lines = sink.lines()
        assertEquals(2, lines.size)
        assertEquals("u-42", parse(lines[0])["user_id"])
        assertNull(parse(lines[1])["user_id"], "parent 不应继承 child 的 userId")
    }

    @Test
    fun setLevelFiltersManualButNotAuto() {
        val (logger, sink) = logger(minLevel = Level.ERROR)
        logger.info("dropped")
        logger.error("kept")
        logger.recordAuto(EventType.HANG, "blocked")
        val lines = sink.lines()
        assertEquals(2, lines.size)
        val msgs = lines.map { parse(it)["message"] }
        assertTrue(msgs.contains("kept"))
        assertTrue(msgs.contains("blocked"))
    }

    @Test
    fun redactAppendsNotReplacesDefaults() {
        val (logger, sink) = logger(minLevel = Level.TRACE)
        logger.redact(listOf("ssn"))
        logger.info("login", payload = mapOf("password" to "hunter2", "ssn" to "123"))
        val payload = parse(sink.lines().single())["payload"] as Map<String, Any?>
        assertEquals("***REDACTED***", payload["password"])
        assertEquals("***REDACTED***", payload["ssn"])
    }

    @Test
    fun emitWritesSingleJsonLine() {
        val (logger, sink) = logger(minLevel = Level.TRACE, redactEnabled = false)
        logger.info(
            "hello",
            category = "cat",
            traceId = "0123456789abcdef0123456789abcde1",
            payload = mapOf("k" to "v"),
        )
        val line = sink.lines().single() // MemorySink 存原始行（不含换行）
        assertFalse(line.contains('\n'), "应为单行 JSON（不含换行）")
        val obj = parse(line)
        assertEquals("hello", obj["message"])
        assertEquals("cat", obj["category"])
        assertEquals("0123456789abcdef0123456789abcde1", obj["trace_id"])
        assertEquals("v", (obj["payload"] as Map<String, Any?>)["k"])
        assertEquals(false, obj["agent_auto"])
    }

    @Test
    fun businessFieldsGoToPayloadNotTopLevel() {
        val (logger, sink) = logger(minLevel = Level.TRACE, redactEnabled = false)
        logger.info("x", payload = mapOf("orderId" to "o1", "amount" to 99))
        val obj = parse(sink.lines().single())
        assertNull(obj["orderId"])
        assertNull(obj["amount"])
        assertEquals("o1", (obj["payload"] as Map<String, Any?>)["orderId"])
        assertEquals(99L, (obj["payload"] as Map<String, Any?>)["amount"])
    }
}
