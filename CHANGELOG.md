# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.2.0] - 2026-11-07

### Changed（Schema v1.2，破坏性）

- `trace_id` 收紧为 **32 位小写 hex**（8 字节，与 OTLP `traceId` / W3C traceparent
  trace-id 同构）。大写自动归一；非法值在序列化边界**丢弃该字段、事件照写**，与
  `span_id` 策略一致。适配器不再对非 hex 值做 `sha256("agentlog:trace:"+raw)` 派生。
  发布时尚无生产使用方（Schema v1.2 描述内已注明）。
- session id 默认生成值改为 32 位小写 hex：`trace_id = session_id` 这个最自然的
  绑定永远合法。

### Added

- `newTraceId()`：生成 Schema 合法的 trace id（uuid4 去横线）。

### Added
- `example/`：可独立运行的 Android 演示 App，演示 `bootstrap` + 四种自动埋点（screen_view 自动、crash/hang/network 按钮触发），用于手测自动埋点行为。
- 全模块接入 **ktlint**（v12.1.2）静态检查，并遵循 `root` `.editorconfig`；CI 与 `ai-dev-spec` gate-runner（`tooling/adapters/kotlin.mjs` 的 `lint` 阶段）均会执行 `./gradlew ktlintCheck`。
- Schema v1.1：可选顶层字段 `span_id`（16 位小写十六进制，与 W3C `traceparent` 的 span-id 同构）。通过 `Logger.withSpan(traceId, spanId)` 或 `child(mapOf("span_id" to …))` 绑定，`child` 同时接受 camelCase / snake_case 保留键，线格式恒为 snake_case；非法值直接丢弃、不写入，绝不产出违反 Schema 的行。
- OTLP/JSON 适配器 `OtlpMapper`（`agentlog-core`，零第三方依赖，复用包内 `Json` 树）：每事件 → 1 条 `LogRecord`；`network_request` / `db_query` 额外产出 `SPAN_KIND_CLIENT`(3) span（HTTP / Database 语义约定）；6 档 level → severity 1/5/9/13/17/21（`critical` → `FATAL`）；`payload.*` 展平一层；int64（纳秒 / 计数）字符串化。入口：`toOtlp` / `toOtlpLogs` / `toOtlpSpans` / `eventToLogRecord` / `eventToSpan` / `toOtlpJson` / 内嵌 `OtlpSink`。
- OTLP id **确定性派生**（无随机，重试幂等）：32 位 hex 的 `trace_id` 原样转小写透传，其余 `sha256("agentlog:trace:"+raw)[:32]`；无 trace 的 span 回落到 `sha256("agentlog:session:"+session_id)[:32]`；缺失 `span_id` 时按 `session_id|trace_id|event_type|ts|message` 派生，事件自带且合法则**优先透传**；`LogRecord` 仅在有 traceId 时写 `spanId`，避免产生孤立 span。
- 批次按 `(service, env, session_id)` 分组且保序，scope 固定 `{name:"agentlog.otlp", version:"1.0.0"}`；`extraResource` 与 agent-log 自身属性冲突时**丢弃冲突键**，保证内置字段不被覆盖。
- `EventType` 补 `DB_QUERY("db_query")`（与 Java/Go/Python/Kotlin 其余端对齐，此前 Android 缺失导致 `db_query` 事件只能走字符串路径）。
- `OtlpMapperTests`（25 项）：severity 表、AnyValue 类型、时间戳（含 `+0800` 与空格分隔）、id 派生与透传、LogRecord 形状、HTTP/DB span、legacy `sql`/`query` 兼容读取、resource 分组、`extraResource` 冲突。

### Changed
- `agentlog-android` 对 `agentlog-core` 的依赖由 `implementation` 改为 `api`，使 core 的公开类型（`AgentLog` / `Level` / `LoggingConfig` 等）对消费者（示例 App、业务 App）传递可见。
- `AgentNetworkInterceptor` 由 `NetworkInterceptor.kt` 重命名为与类名一致的 `AgentNetworkInterceptor.kt`。

## [0.1.0] - 2026-11-07

### Added
- `agentlog-core`：纯 Kotlin/JVM、**零第三方依赖**的核心日志库，对齐语言中立契约（`emit` / `child` / `setLevel` / `redact`），含手写 snake_case JSONL 序列化与最小化解析器、`Level`（6 档词表）、`Redactor`、`Session`、`EventType`、`LoggingConfig`、`SchemaTests`（对齐 Swift 字段集断言）。
- `agentlog-android`：AGP 库，提供 `AgentLogAndroid.bootstrap` 自动埋点（生命周期→`screen_view`、未捕获异常→`crash`、主线程卡顿>250ms→`hang`、OkHttp 拦截器→`network_request` + `traceparent` 注入）。
- 根 Gradle 工程：单一命令构建两个模块（`./gradlew :agentlog-core:test` / `./gradlew :agentlog-android:build`）；`agentlog-core` 另保留独立 wrapper 以无 Android SDK 跑测试。
- 自描述文档：`docs/INTERFACE.md`（契约方法面）、`docs/schema/log-event.schema.json`（Schema v1 线格式）、`docs/schema/example.json`（示例行）。
- GitHub Actions CI：`core` job（JDK17，无 SDK 跑核心测试）与 `android` job（JDK17 + Android SDK 跑 AGP build/lint）。
