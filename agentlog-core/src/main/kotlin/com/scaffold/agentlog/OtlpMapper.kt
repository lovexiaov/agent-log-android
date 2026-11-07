package com.scaffold.agentlog

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * OTLP 导出适配器：Schema v1 事件 → OpenTelemetry OTLP/JSON。
 *
 * D3「OTel 对齐」能力：**不改线格式**，只做单向翻译，把已经写出的事件变成 OTLP，
 * 从而能通过任意 OTLP Collector 送往 Jaeger / Tempo / Loki / Datadog。
 *
 * 规范真源：`docs/logging-framework/OTLP-MAPPING.md`。
 * - 每条事件 → 一条 OTLP `LogRecord`；
 * - `network_request` → 额外一条 `SPAN_KIND_CLIENT` span（HTTP 语义约定）；
 * - `db_query` → 额外一条 `SPAN_KIND_CLIENT` span（Database 语义约定）；
 * - `crash` / `hang` → 只发 logs，附 `exception.*` 属性。
 *
 * 设计约束：
 * - **零第三方依赖**：产出 [JsonValue] 树，由 [Json] 手写序列化（与 JSONL 同一条路径）；
 * - **id 确定性派生**（SHA-256，绝不随机）：同一事件重复导出得到同一组 id，重试幂等；
 * - `payload` 只**展平一层**，嵌套对象/数组保持 `kvlistValue`/`arrayValue` 结构。
 */
object OtlpMapper {
    /** 与项目版本保持一致（测试断言）。 */
    const val SDK_VERSION: String = "1.0.0"

    /** OTel `SpanKind.SPAN_KIND_CLIENT`。 */
    const val SPAN_KIND_CLIENT: Int = 3

    /** OTel `Status.StatusCode.STATUS_CODE_ERROR`。 */
    const val STATUS_CODE_ERROR: Int = 2

    /** 级别到 OTel logs 数据模型的翻译结果。 */
    data class Severity(val text: String, val number: Int)

    // OTel 语义约定键（部分仍为 Experimental，统一收口在这里）。
    private const val SERVICE_NAME = "service.name"
    private const val DEPLOYMENT_ENV = "deployment.environment"
    private const val SESSION_ID = "session.id"
    private const val ENDUSER_ID = "enduser.id"
    private const val HTTP_METHOD = "http.request.method"
    private const val URL_FULL = "url.full"
    private const val HTTP_STATUS = "http.response.status_code"
    private const val SERVER_ADDRESS = "server.address"
    private const val DB_SYSTEM = "db.system"
    private const val DB_OPERATION = "db.operation"
    private const val DB_STATEMENT = "db.statement"
    private const val ERROR_TYPE = "error.type"
    private const val EXCEPTION_TYPE = "exception.type"
    private const val EXCEPTION_MESSAGE = "exception.message"
    private const val EXCEPTION_STACKTRACE = "exception.stacktrace"

    // agent-log 自己的属性命名空间（避免与 semconv 撞键）。
    private const val ATTR_EVENT_TYPE = "agent.event.type"
    private const val ATTR_AUTO = "agent.auto"
    private const val ATTR_SUBSYSTEM = "agent.subsystem"
    private const val ATTR_CATEGORY = "agent.category"

    // 操作类 span 的 payload 键候选（兼容历史写法）。
    private val HTTP_METHOD_KEYS = listOf("method", "http_method", "http.method")
    private val URL_KEYS = listOf("url", "url_full", "url.full")
    private val STATUS_KEYS = listOf("status_code", "status", "statusCode", "http.status_code")
    private val DURATION_KEYS = listOf("duration_ms", "durationMs", "duration")

    /** 规范键是 `statement`；`sql` / `query` 仅读取侧兼容旧数据（Schema v1.1）。 */
    private val SQL_KEYS = listOf("statement", "sql", "query")
    private val DB_SYSTEM_KEYS = listOf("db_system", "db.system", "system")
    private val DB_OPERATION_KEYS = listOf("operation", "op")

    private val TS_RE = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?$",
    )

    private val URL_HOST_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)")

    // ---------------------------------------------------------------- 构造工具

    private fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(linkedMapOf(*pairs))

    private fun str(value: String): JsonValue = JsonValue.Str(value)

    private fun int(value: Long): JsonValue = JsonValue.IntNum(value)

    private fun int(value: Int): JsonValue = JsonValue.IntNum(value.toLong())

    private fun bool(value: Boolean): JsonValue = JsonValue.Bool(value)

    private fun arr(values: List<JsonValue>): JsonValue = JsonValue.Arr(values)

    // -------------------------------------------------------------- 值转换

    /**
     * 转成 OTLP `AnyValue`（protobuf JSON 形式）。
     *
     * `int64` 必须**字符串化**：JSON 数字是 double，装不下 64 位整数。
     */
    fun toAnyValue(value: JsonValue): JsonValue.Obj = when (value) {
        is JsonValue.Bool -> obj("boolValue" to bool(value.value))
        is JsonValue.IntNum -> obj("intValue" to str(value.value.toString()))
        is JsonValue.FloatNum -> obj("doubleValue" to JsonValue.FloatNum(value.value))
        is JsonValue.Str -> obj("stringValue" to value)
        is JsonValue.Arr -> obj("arrayValue" to obj("values" to arr(value.value.map { toAnyValue(it) })))
        is JsonValue.Obj -> obj("kvlistValue" to obj("values" to arr(keyValues(value.value))))
        JsonValue.Null -> obj("stringValue" to str(""))
    }

    /** 构造一个 OTLP `KeyValue`。 */
    fun toKeyValue(key: String, value: JsonValue): JsonValue.Obj = obj("key" to str(key), "value" to toAnyValue(value))

    /** 把对象铺平成 `KeyValue` 列表（[JsonValue.Null] 跳过）。 */
    private fun keyValues(mapping: Map<String, JsonValue>, prefix: String = ""): List<JsonValue> = mapping.entries
        .filter { it.value != JsonValue.Null }
        .map { (key, value) -> toKeyValue(prefix + key, value) as JsonValue }

    // ------------------------------------------------------------------ 时间

    /**
     * RFC3339 / ISO8601 → epoch 纳秒。
     *
     * 超过纳秒的精度直接截断（不四舍五入）；无偏移的时间戳按 UTC 处理。
     */
    fun parseTimestampNanos(ts: String): Long {
        val match = TS_RE.matchEntire(ts.trim())
            ?: throw IllegalArgumentException("not an RFC3339 timestamp: $ts")
        val groups = match.groupValues
        val year = groups[1].toInt()
        val month = groups[2].toInt()
        val day = groups[3].toInt()
        val hour = groups[4].toInt()
        val minute = groups[5].toInt()
        val second = groups[6].toInt()

        val frac = groups[7]
        val nanos = if (frac.isEmpty()) 0L else (frac + "000000000").substring(0, 9).toLong()

        val offset = parseOffset(groups[8])
        val epochSecond = LocalDateTime.of(year, month, day, hour, minute, second).toEpochSecond(offset)
        return epochSecond * 1_000_000_000L + nanos
    }

    private fun parseOffset(raw: String): ZoneOffset {
        if (raw.isEmpty() || raw == "Z" || raw == "z") return ZoneOffset.UTC
        val sign = if (raw[0] == '-') -1 else 1
        val digits = raw.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toInt()
        val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
        return ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60))
    }

    // -------------------------------------------------------------------- id

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(StandardCharsets.UTF_8))
        return hash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    /**
     * 事件的 OTLP `traceId`（32 位 hex），无合法 `trace_id` 时返回 `null`。
     *
     * Schema v1.2：`trace_id` 必须是 32 位 hex（大写归一）。非法值不派生、不变形
     * —— 写入边界已丢弃，到这里的非法值只能来自手工构造的字典，同样按无 trace 处理。
     */
    fun traceIdHex(event: LogEvent): String? = TraceId.normalize(event.traceId)

    /**
     * 事件的 OTLP `spanId`（16 位 hex）。
     *
     * Schema v1.1 的 `span_id` 合法时**优先透传**（日志行与链路节点指向同一个真实 span）；
     * 缺失或非法时确定性派生。
     */
    fun spanIdHex(event: LogEvent): String {
        SpanId.normalize(event.spanId)?.let { return it }
        val seed = listOf(
            "agentlog:span",
            event.sessionId,
            event.traceId ?: "",
            event.eventType,
            event.ts,
            event.message,
        ).joinToString("|")
        return sha256Hex(seed).substring(0, 16)
    }

    /**
     * span 的 trace id：无 `trace_id` 时回退到会话级合成 trace。
     *
     * span 不能脱离 trace 存在；同一会话的多个 span 归入同一条合成 trace，
     * 否则在 Jaeger 里会变成一堆孤立单 span 链路。
     */
    fun spanTraceId(event: LogEvent): String =
        traceIdHex(event) ?: sha256Hex("agentlog:session:${event.sessionId}").substring(0, 32)

    // ------------------------------------------------------------------ 级别

    /**
     * agent-log level → `severityText` / `severityNumber`。
     *
     * 读取侧别名 `warning` 归一化为 `warn`；未知 level **降级为 INFO**，
     * 不让一次导出因为脏数据整体失败。
     */
    fun levelToSeverity(level: String?): Severity = when (Level.fromWire(level ?: "info") ?: Level.INFO) {
        Level.TRACE -> Severity("TRACE", 1)
        Level.DEBUG -> Severity("DEBUG", 5)
        Level.INFO -> Severity("INFO", 9)
        Level.WARN -> Severity("WARN", 13)
        Level.ERROR -> Severity("ERROR", 17)
        Level.CRITICAL -> Severity("FATAL", 21)
    }

    // --------------------------------------------------------------- resource

    private fun scope(): JsonValue.Obj = obj("name" to str("agentlog.otlp"), "version" to str(SDK_VERSION))

    private fun resourceAttributes(event: LogEvent): MutableList<JsonValue> {
        val attrs = mutableListOf<JsonValue>()
        attrs.add(toKeyValue(SERVICE_NAME, str(event.service.ifEmpty { "app" })))
        event.env?.takeIf { it.isNotEmpty() }?.let { attrs.add(toKeyValue(DEPLOYMENT_ENV, str(it))) }
        event.sessionId.takeIf { it.isNotEmpty() }?.let { attrs.add(toKeyValue(SESSION_ID, str(it))) }
        attrs.add(toKeyValue("telemetry.sdk.name", str("agent-log")))
        attrs.add(toKeyValue("telemetry.sdk.language", str("kotlin")))
        attrs.add(toKeyValue("telemetry.sdk.version", str(SDK_VERSION)))
        return attrs
    }

    /** 按 (service, env, session_id) 分组，保持输入顺序。 */
    private fun groupByResource(events: List<LogEvent>): List<List<LogEvent>> {
        val groups = LinkedHashMap<String, MutableList<LogEvent>>()
        for (event in events) {
            val key = "${event.service.ifEmpty { "app" }}\u0000${event.env ?: ""}\u0000${event.sessionId}"
            groups.getOrPut(key) { mutableListOf() }.add(event)
        }
        return groups.values.toList()
    }

    /**
     * 追加调用方资源属性，但**跳过 agent-log 已占用的键**。
     *
     * OTLP 里重复键由后端随意裁决（通常后者胜），所以「自身字段优先」必须在这里
     * 靠直接丢弃冲突键来保证。
     */
    private fun mergeExtraResource(
        resource: MutableList<JsonValue>,
        extraResource: Map<String, JsonValue>?,
    ): MutableList<JsonValue> {
        if (extraResource.isNullOrEmpty()) return resource
        val taken = resource.mapNotNull { attributeKey(it) }.toMutableSet()
        for (kv in keyValues(extraResource)) {
            val key = attributeKey(kv) ?: continue
            if (taken.contains(key)) continue
            resource.add(kv)
            taken.add(key)
        }
        return resource
    }

    /** 取一个 `KeyValue` 的 `key` 字段。 */
    private fun attributeKey(kv: JsonValue): String? =
        (((kv as? JsonValue.Obj)?.value?.get("key")) as? JsonValue.Str)?.value

    // -------------------------------------------------------------- LogRecord

    /** 把事件的非 resource 字段翻译成 OTLP 属性。 */
    fun eventAttributes(event: LogEvent): List<JsonValue> {
        val attrs = mutableListOf<JsonValue>()
        attrs.add(toKeyValue(ATTR_EVENT_TYPE, str(event.eventType)))
        attrs.add(toKeyValue(ATTR_AUTO, bool(event.agentAuto)))
        event.subsystem?.takeIf { it.isNotEmpty() }?.let { attrs.add(toKeyValue(ATTR_SUBSYSTEM, str(it))) }
        event.category?.takeIf { it.isNotEmpty() }?.let { attrs.add(toKeyValue(ATTR_CATEGORY, str(it))) }
        // 只放间接标识 —— Schema v1 禁止在这里放直接 PII。
        event.userId?.takeIf { it.isNotEmpty() }?.let { attrs.add(toKeyValue(ENDUSER_ID, str(it))) }
        val payload = event.payload ?: emptyMap()
        attrs.addAll(keyValues(payload, "payload."))
        if (event.eventType == "crash" || event.eventType == "hang") {
            attrs.addAll(exceptionAttributes(payload))
        }
        return attrs
    }

    private fun exceptionAttributes(payload: Map<String, JsonValue>): List<JsonValue> {
        val attrs = mutableListOf<JsonValue>()
        firstOf(payload, listOf("exception", "exception_type"))?.let { attrs.add(toKeyValue(EXCEPTION_TYPE, it)) }
        // crash 的 payload 用 `message` 装异常文本；两种写法都认。
        firstOf(payload, listOf("exception_message", "message"))?.let {
            attrs.add(toKeyValue(EXCEPTION_MESSAGE, it))
        }
        firstOf(payload, listOf("stack", "stacktrace", "trace"))?.let {
            attrs.add(toKeyValue(EXCEPTION_STACKTRACE, it))
        }
        return attrs
    }

    /** 一条 Schema v1 事件 → 一条 OTLP `LogRecord`。 */
    fun eventToLogRecord(event: LogEvent): JsonValue.Obj {
        val severity = levelToSeverity(event.level)
        val timeNanos = parseTimestampNanos(event.ts).toString()

        val fields = linkedMapOf<String, JsonValue>(
            "timeUnixNano" to str(timeNanos),
            "observedTimeUnixNano" to str(timeNanos),
            "severityNumber" to int(severity.number),
            "severityText" to str(severity.text),
            "body" to obj("stringValue" to str(event.message)),
            "attributes" to arr(eventAttributes(event)),
        )

        traceIdHex(event)?.let { traceId ->
            fields["traceId"] = str(traceId)
            // 优先用事件自带的（v1.1）span id —— 它指向真实 span。否则只给操作类事件配 id，
            // 与它产出的 span 对齐，日志行永远不会引用一个不存在的 span。
            val explicit = SpanId.normalize(event.spanId)
            if (explicit != null) {
                fields["spanId"] = str(explicit)
            } else if (spanKind(event) != null) {
                fields["spanId"] = str(spanIdHex(event))
            }
        }
        return JsonValue.Obj(fields)
    }

    // ------------------------------------------------------------------ Span

    private fun firstOf(payload: Map<String, JsonValue>, keys: List<String>): JsonValue? {
        for (key in keys) {
            val value = payload[key] ?: continue
            if (value == JsonValue.Null) continue
            if (value is JsonValue.Str && value.value.isEmpty()) continue
            return value
        }
        return null
    }

    private fun firstText(payload: Map<String, JsonValue>, keys: List<String>): String? =
        when (val value = firstOf(payload, keys)) {
            null -> null
            is JsonValue.Str -> value.value
            is JsonValue.IntNum -> value.value.toString()
            is JsonValue.FloatNum -> value.value.toString()
            is JsonValue.Bool -> value.value.toString()
            else -> null
        }

    private fun toNumber(value: JsonValue?): Double? = when (value) {
        is JsonValue.IntNum -> value.value.toDouble()
        is JsonValue.FloatNum -> value.value
        is JsonValue.Bool -> if (value.value) 1.0 else 0.0
        is JsonValue.Str -> value.value.toDoubleOrNull()
        else -> null
    }

    private fun durationMs(payload: Map<String, JsonValue>): Double = toNumber(firstOf(payload, DURATION_KEYS)) ?: 0.0

    /** 只有 `network_request` / `db_query` 是「调用跨度」。 */
    private fun spanKind(event: LogEvent): Int? =
        if (event.eventType == "network_request" || event.eventType == "db_query") SPAN_KIND_CLIENT else null

    private fun httpSpanName(payload: Map<String, JsonValue>): String {
        val method = firstText(payload, HTTP_METHOD_KEYS) ?: "HTTP"
        val url = firstText(payload, URL_KEYS) ?: ""
        return "$method $url"
    }

    private fun httpError(payload: Map<String, JsonValue>, status: Double?): String? {
        if (status != null && status >= 400) return status.toLong().toString()
        val error = firstText(payload, listOf("error")) ?: return null
        return error.ifEmpty { null }
    }

    private fun httpSpanAttributes(payload: Map<String, JsonValue>): List<JsonValue> {
        val attrs = mutableListOf<JsonValue>()
        val method = firstText(payload, HTTP_METHOD_KEYS)
        val url = firstText(payload, URL_KEYS)
        val status = toNumber(firstOf(payload, STATUS_KEYS))

        method?.let { attrs.add(toKeyValue(HTTP_METHOD, str(it))) }
        url?.let {
            attrs.add(toKeyValue(URL_FULL, str(it)))
            hostOf(it)?.let { host -> attrs.add(toKeyValue(SERVER_ADDRESS, str(host))) }
        }
        // 状态码是整数：始终 intValue，即使 payload 里写成 200.0。
        status?.let { attrs.add(toKeyValue(HTTP_STATUS, int(it.toLong()))) }
        httpError(payload, status)?.let { attrs.add(toKeyValue(ERROR_TYPE, str(it))) }
        return attrs
    }

    /** 取绝对 URL 的 host 部分（`server.address`）。 */
    private fun hostOf(url: String): String? {
        val hostPort = URL_HOST_RE.find(url)?.groupValues?.get(1) ?: return null
        val at = hostPort.lastIndexOf('@')
        val host = if (at >= 0) hostPort.substring(at + 1) else hostPort
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            return if (close > 0) host.substring(1, close) else host
        }
        val colon = host.indexOf(':')
        return if (colon >= 0) host.substring(0, colon) else host
    }

    private fun dbSpanName(payload: Map<String, JsonValue>): String {
        val operation = firstText(payload, DB_OPERATION_KEYS) ?: "SQL"
        val statement = firstText(payload, SQL_KEYS) ?: ""
        return "$operation $statement"
    }

    private fun dbError(payload: Map<String, JsonValue>): String? {
        firstText(payload, listOf("error"))?.takeIf { it.isNotEmpty() }?.let { return it }
        return firstText(payload, listOf("error_type"))?.takeIf { it.isNotEmpty() }
    }

    private fun dbSpanAttributes(payload: Map<String, JsonValue>): List<JsonValue> {
        val attrs = mutableListOf<JsonValue>()
        firstText(payload, DB_SYSTEM_KEYS)?.let { attrs.add(toKeyValue(DB_SYSTEM, str(it))) }
        firstText(payload, DB_OPERATION_KEYS)?.let { attrs.add(toKeyValue(DB_OPERATION, str(it))) }
        firstText(payload, SQL_KEYS)?.let { attrs.add(toKeyValue(DB_STATEMENT, str(it))) }
        dbError(payload)?.let { attrs.add(toKeyValue(ERROR_TYPE, str(it))) }
        return attrs
    }

    /** 一条操作事件 → 一条 OTLP `Span`；非操作事件返回 `null`。 */
    fun eventToSpan(event: LogEvent): JsonValue.Obj? {
        val kind = spanKind(event) ?: return null
        val payload = event.payload ?: emptyMap()
        val isHttp = event.eventType == "network_request"
        val name = if (isHttp) httpSpanName(payload) else dbSpanName(payload)
        val attrs = if (isHttp) httpSpanAttributes(payload) else dbSpanAttributes(payload)
        val failed = if (isHttp) {
            httpError(payload, toNumber(firstOf(payload, STATUS_KEYS))) != null
        } else {
            dbError(payload) != null
        }

        val start = parseTimestampNanos(event.ts)
        val end = start + (durationMs(payload) * 1_000_000.0).toLong()

        val fields = linkedMapOf<String, JsonValue>(
            "traceId" to str(spanTraceId(event)),
            "spanId" to str(spanIdHex(event)),
            "name" to str(name),
            "kind" to int(kind),
            "startTimeUnixNano" to str(start.toString()),
            "endTimeUnixNano" to str(end.toString()),
            "attributes" to arr(attrs),
        )
        if (failed) {
            fields["status"] = obj("code" to int(STATUS_CODE_ERROR), "message" to str(name))
        }
        return JsonValue.Obj(fields)
    }

    // ----------------------------------------------------------------- 批次

    /** 构造 OTLP/JSON `ExportLogsServiceRequest`。 */
    fun toOtlpLogs(events: List<LogEvent>, extraResource: Map<String, JsonValue>? = null): JsonValue.Obj {
        val resourceLogs = groupByResource(events).map { group ->
            val records = group.map { eventToLogRecord(it) as JsonValue }
            val attributes = mergeExtraResource(resourceAttributes(group.first()), extraResource)
            obj(
                "resource" to obj("attributes" to arr(attributes)),
                "scopeLogs" to arr(listOf(obj("scope" to scope(), "logRecords" to arr(records)))),
            ) as JsonValue
        }
        return obj("resourceLogs" to arr(resourceLogs))
    }

    /** 构造 OTLP/JSON `ExportTraceServiceRequest`。 */
    fun toOtlpSpans(events: List<LogEvent>, extraResource: Map<String, JsonValue>? = null): JsonValue.Obj {
        val resourceSpans = mutableListOf<JsonValue>()
        for (group in groupByResource(events)) {
            val spans = group.mapNotNull { eventToSpan(it) as JsonValue? }
            // 没有操作事件：整组跳过，而不是发一个空的 resource 块（以及一个空请求）。
            if (spans.isEmpty()) continue
            resourceSpans.add(
                obj(
                    "resource" to obj(
                        "attributes" to arr(mergeExtraResource(resourceAttributes(group.first()), extraResource)),
                    ),
                    "scopeSpans" to arr(listOf(obj("scope" to scope(), "spans" to arr(spans)))),
                ),
            )
        }
        return obj("resourceSpans" to arr(resourceSpans))
    }

    /**
     * 一次产出两种信号。
     *
     * OTLP 一次请求只承载一种信号：`logs` POST 到 `/v1/logs`，`traces` POST 到 `/v1/traces`。
     */
    fun toOtlp(events: List<LogEvent>, extraResource: Map<String, JsonValue>? = null): JsonValue.Obj = obj(
        "logs" to toOtlpLogs(events, extraResource),
        "traces" to toOtlpSpans(events, extraResource),
    )

    /** 序列化为紧凑 JSON。 */
    fun toOtlpJson(payload: JsonValue): String = StringBuilder().also { Json.writeValue(it, payload) }.toString()

    /** 把一行 JSONL 解析回 [LogEvent]（sink 用；解析器与 JSONL 写入共用最小实现）。 */
    private fun parseEvent(line: String): LogEvent {
        val map = Json.parse(line) as? Map<*, *> ?: error("not a JSON object: $line")
        fun text(key: String): String = map[key]?.toString() ?: ""
        fun optional(key: String): String? = (map[key] as? String)?.takeIf { it.isNotEmpty() }
        val payload = (map["payload"] as? Map<*, *>)?.entries
            ?.associate { (key, value) -> key.toString() to value.toJsonValue() }
        return LogEvent(
            ts = text("ts"),
            level = text("level"),
            service = text("service"),
            subsystem = optional("subsystem"),
            category = optional("category"),
            eventType = text("event_type"),
            sessionId = text("session_id"),
            traceId = optional("trace_id"),
            spanId = optional("span_id"),
            agentAuto = map["agent_auto"] == true,
            message = text("message"),
            env = optional("env"),
            userId = optional("user_id"),
            payload = payload,
        )
    }

    /**
     * 缓冲事件行、按需产出 OTLP 批次的 sink。
     *
     * 存的是**已序列化**的行，因此脱敏与 minLevel 过滤都已由 logger 完成。
     */
    class OtlpSink(private val extraResource: Map<String, JsonValue>? = null) : LogSink {
        private val lines = mutableListOf<String>()
        private val lock = Any()

        override fun write(line: String) {
            synchronized(lock) { lines.add(line) }
        }

        fun lines(): List<String> = synchronized(lock) { lines.toList() }

        fun size(): Int = synchronized(lock) { lines.size }

        /** 取出 OTLP 批次并清空缓冲。 */
        fun drain(): JsonValue.Obj {
            val drained = synchronized(lock) {
                val copy = lines.toList()
                lines.clear()
                copy
            }
            val events = drained.map { parseEvent(it) }
            return toOtlp(events, extraResource)
        }
    }
}
