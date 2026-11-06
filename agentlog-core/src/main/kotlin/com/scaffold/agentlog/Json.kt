package com.scaffold.agentlog

/**
 * 结构化值（对齐 Swift `AgentValue` / Schema `payload` 任意类型）。
 * 顶层字段集由 [LogEvent] 封闭，payload 内值为任意可序列化结构。
 */
sealed interface JsonValue {
    data class Str(val value: String) : JsonValue
    data class IntNum(val value: Long) : JsonValue
    data class FloatNum(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
    data class Obj(val value: Map<String, JsonValue>) : JsonValue
    data class Arr(val value: List<JsonValue>) : JsonValue
}

/** 把常见 Kotlin 类型转为 [JsonValue]（payload 构造便利）。 */
fun Any?.toJsonValue(): JsonValue = when (this) {
    null -> JsonValue.Null
    is JsonValue -> this
    is Boolean -> JsonValue.Bool(this)
    is Int -> JsonValue.IntNum(this.toLong())
    is Long -> JsonValue.IntNum(this)
    is Short -> JsonValue.IntNum(this.toLong())
    is Byte -> JsonValue.IntNum(this.toLong())
    is Double -> JsonValue.FloatNum(this)
    is Float -> JsonValue.FloatNum(this.toDouble())
    is String -> JsonValue.Str(this)
    is Map<*, *> -> JsonValue.Obj(
        this.mapKeys { it.key.toString() }.mapValues { it.value.toJsonValue() }
    )
    is List<*> -> JsonValue.Arr(this.map { it.toJsonValue() })
    is Array<*> -> JsonValue.Arr(this.map { it.toJsonValue() })
    else -> JsonValue.Str(this.toString())
}

/**
 * 最小化 JSON 工具：
 * - 手写序列化（显式 snake_case，不开反射），保证零第三方依赖、本机可编译可测；
 * - 提供足够覆盖 Schema v1 固定结构与 `config/logging.config.json` 的解析器（供测试 round-trip 与配置读取）。
 */
object Json {
    /** 字符串转义，保证 JSON 合法（覆盖引号、反斜杠、控制字符）。 */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code))
                else sb.append(c)
            }
        }
        return sb.toString()
    }

    fun quote(s: String): String = "\"" + escape(s) + "\""

    /** 把 [JsonValue] 写成 JSON 片段。 */
    fun writeValue(sb: StringBuilder, v: JsonValue) {
        when (v) {
            is JsonValue.Str -> sb.append(quote(v.value))
            is JsonValue.IntNum -> sb.append(v.value.toString())
            is JsonValue.FloatNum -> {
                val d = v.value
                if (d.isFinite() && d == d.toLong().toDouble()) sb.append(d.toLong().toString())
                else sb.append(d.toString())
            }
            is JsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            JsonValue.Null -> sb.append("null")
            is JsonValue.Obj -> writeObj(sb, v.value)
            is JsonValue.Arr -> {
                sb.append('[')
                v.value.forEachIndexed { i, e -> if (i > 0) sb.append(','); writeValue(sb, e) }
                sb.append(']')
            }
        }
    }

    fun writeObj(sb: StringBuilder, obj: Map<String, JsonValue>) {
        sb.append('{')
        obj.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append(quote(k)).append(':')
            writeValue(sb, v)
        }
        sb.append('}')
    }

    // ---- 最小化 JSON 解析（覆盖 Schema v1 固定结构与 config） ----
    fun parse(json: String): Any? = Parser(json).parse()

    private class Parser(private val src: String) {
        private var i = 0

        fun parse(): Any? {
            skipWs()
            return parseValue().also { skipWs() }
        }

        private fun parseValue(): Any? {
            skipWs()
            if (i >= src.length) return null
            return when (src[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun skipWs() {
            while (i < src.length && src[i] in " \t\r\n") i++
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{'); skipWs()
            val map = LinkedHashMap<String, Any?>()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs(); val key = parseString(); skipWs(); expect(':')
                val value = parseValue(); map[key] = value
                skipWs()
                if (peek() == ',') { i++; continue } else break
            }
            skipWs(); expect('}')
            return map
        }

        private fun parseArray(): List<Any?> {
            expect('['); skipWs()
            val list = mutableListOf<Any?>()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (peek() == ',') { i++; continue } else break
            }
            skipWs(); expect(']')
            return list
        }

        private fun parseString(): String {
            expect('"'); val sb = StringBuilder()
            while (i < src.length) {
                val c = src[i++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    val e = src[i++]
                    when (e) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\')
                        '/' -> sb.append('/'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'b' -> sb.append('\u0008'); 'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = src.substring(i, i + 4); i += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(e)
                    }
                } else sb.append(c)
            }
            return sb.toString()
        }

        private fun parseNumber(): Any? {
            val start = i
            while (i < src.length && src[i] in "+-0123456789.eE") i++
            val s = src.substring(start, i)
            return if (s.contains('.') || s.contains('e') || s.contains('E')) s.toDoubleOrNull()
            else s.toLongOrNull()
        }

        private fun parseBool(): Boolean {
            if (src.startsWith("true", i)) { i += 4; return true }
            i += 5; return false
        }

        private fun parseNull(): Any? { i += 4; return null }

        private fun peek(): Char? = if (i < src.length) src[i] else null
        private fun expect(c: Char) {
            if (i < src.length && src[i] == c) i++ else error("Expected '$c' at index $i")
        }
    }
}
