package cn.lovexiaov.agent.log

/**
 * 日志级别（Schema v1 词表：trace<debug<info<warn<error<critical）。
 * 线格式只允许这 6 个值；Kotlin 内部枚举名为 [WARN]，但写出**必须**是 `warn`；
 * `warning` 仅作读取端兼容别名（历史日志），聚合时归一化。
 */
enum class Level(val agentName: String, val weight: Int) {
    TRACE("trace", 0),
    DEBUG("debug", 1),
    INFO("info", 2),
    WARN("warn", 3),
    ERROR("error", 4),
    CRITICAL("critical", 5),
    ;

    companion object {
        fun fromName(name: String): Level? = entries.firstOrNull { it.agentName == name }

        /** 解析：写入端只接受 6 个值；`warning` 归一化为 [WARN]。 */
        fun fromWire(name: String): Level? = when (name) {
            "warn", "warning" -> WARN
            else -> fromName(name)
        }
    }
}
