# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- `example/`：可独立运行的 Android 演示 App，演示 `bootstrap` + 四种自动埋点（screen_view 自动、crash/hang/network 按钮触发），用于手测自动埋点行为。
- 全模块接入 **ktlint**（v12.1.2）静态检查，并遵循 `root` `.editorconfig`；CI 与 `ai-dev-spec` gate-runner（`tooling/adapters/kotlin.mjs` 的 `lint` 阶段）均会执行 `./gradlew ktlintCheck`。
- Schema v1.1：可选顶层字段 `span_id`（16 位小写十六进制，与 W3C `traceparent` 的 span-id 同构）。通过 `Logger.withSpan(traceId, spanId)` 或 `child(mapOf("span_id" to …))` 绑定，`child` 同时接受 camelCase / snake_case 保留键，线格式恒为 snake_case；非法值直接丢弃、不写入，绝不产出违反 Schema 的行。

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
