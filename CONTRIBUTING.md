# Contributing

欢迎提交 Issue 与 PR。

## 本地开发

- 根工程构建：
  - `./gradlew :agentlog-core:test` —— 核心测试（16 个，含 `SchemaTests`），**无需 Android SDK**。
  - `./gradlew :agentlog-android:build` —— AAR 产出 + lint（需 `ANDROID_HOME` 指向 Android SDK）。
- 纯 JVM 校验（完全无 SDK 环境）：`cd agentlog-core && ./gradlew test`（`agentlog-core` 自带独立 wrapper）。

## 契约约束（务必遵守）

- 线格式必须可通过 [`docs/schema/log-event.schema.json`](docs/schema/log-event.schema.json) 校验：字段集封闭、snake_case、必填齐全、非保留键一律进 `payload`。
- 脱敏在 logger 层强制；默认脱敏键**只可增不可减**。
- 任何影响线格式的改动，须同步更新 Schema 与 `agentlog-core` 的 `SchemaTests`，保持与 Web / macOS 端逐字一致。

## 提交规范

- 遵循 [Conventional Commits](https://www.conventionalcommits.org/)（`feat:` / `fix:` / `docs:` / `chore:` …）。
- 本仓库以 **git submodule** 形式挂入 `ai-dev-spec`（`logging-framework/android/AgentLog`）。在此仓库完成改动并 push 后，再到脚手架执行 `git submodule update --remote logging-framework/android/AgentLog` 同步 pin。
