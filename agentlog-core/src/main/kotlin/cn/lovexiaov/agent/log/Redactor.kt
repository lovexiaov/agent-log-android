package cn.lovexiaov.agent.log

/**
 * 日志层强制脱敏（不依赖调用方自觉），与 Web / Swift 端策略一致（C6）。
 *
 * - 默认脱敏键**不可关闭**，只能追加（[withAdditional]）。
 * - 命中敏感 key 直接整体掩码；自由文本走正则脱敏；对象/数组递归脱敏。
 * - 同时支持 path 末段匹配：如 `user.email` 命中 payload 键 `email`。
 */
class Redactor(
    sensitiveKeys: Set<String> = DEFAULT_SENSITIVE_KEYS,
    additional: Set<String> = emptySet(),
) {
    companion object {
        val DEFAULT_SENSITIVE_KEYS: Set<String> = setOf(
            "password", "passwd", "pwd", "token", "authorization", "auth",
            "cookie", "secret", "apikey", "api_key", "accesstoken", "access_token",
            "refreshtoken", "refresh_token", "credential", "privatekey", "private_key", "ssn", "bearer",
        )
        private const val MASK = "***REDACTED***"

        // 匹配 `key=value` / `key: value` / `Authorization: Bearer <token>` 中的敏感值
        private val PATTERN = Regex(
            """(?i)((?:password|passwd|pwd|token|authorization|auth|cookie|secret|""" +
                """api[_-]?key|access[_-]?token|refresh[_-]?token|bearer)(?:\s*[:=]\s*['"]?)?\s*""" +
                """(?:bearer\s+)?)([^'";,\s]+)""",
        )
    }

    private val keys: Set<String> = sensitiveKeys + additional

    /** 追加脱敏路径（默认键不可关闭，只能追加）。 */
    fun withAdditional(more: Collection<String>): Redactor = Redactor(keys, more.toSet())

    /** 对自由文本做正则脱敏。 */
    fun redact(text: String): String = PATTERN.replace(text) { m -> m.groupValues[1] + MASK }

    /** 按 payload 的 key 名脱敏（命中敏感 key 整体替换为掩码）。 */
    fun maskSensitiveKeys(payload: Map<String, JsonValue>): Map<String, JsonValue> =
        payload.mapValues { (k, v) -> if (shouldMask(k)) JsonValue.Str(MASK) else redactValue(v) }

    /** 对 value 递归脱敏（字符串值走正则；对象/数组递归）。 */
    fun redactValue(value: JsonValue): JsonValue = when (value) {
        is JsonValue.Str -> JsonValue.Str(redact(value.value))
        is JsonValue.Obj -> JsonValue.Obj(value.value.mapValues { (_, v) -> redactValue(v) })
        is JsonValue.Arr -> JsonValue.Arr(value.value.map { redactValue(it) })
        else -> value
    }

    /** 是否应掩码：归一化（忽略大小写与 `_-`）后命中，或命中 path 末段。 */
    fun shouldMask(key: String): Boolean {
        val norm = key.lowercase().replace(Regex("[-_]"), "")
        if (keys.any { it.lowercase().replace(Regex("[-_]"), "") == norm }) return true
        val last = key.lowercase().split('.').last()
        if (keys.any { it.lowercase().split('.').last() == last }) return true
        return false
    }
}
