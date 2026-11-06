# AgentLog — 面向 Agent 的 Android (Kotlin) 日志框架

[![CI](https://github.com/lovexiaov/agent-log-android/actions/workflows/ci.yml/badge.svg)](https://github.com/lovexiaov/agent-log-android/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

为多个项目提供**统一、面向 Agent（AI 调试/排障）**的日志接入规范。本期实现 **Android (Kotlin)**，基于成熟开源方案组合（`kotlin-stdlib` + `okhttp` 拦截器），不重复造轮子，线格式与 Web / macOS 端**逐字相同**（Schema v1）。

- **轻量便捷**：Gradle 一处依赖 + Application 一处 `bootstrap`。
- **低侵入**：自动埋点默认全开，**业务侧零埋点代码**。
- **Agent 友好**：结构化 JSONL 落盘（`Context.cacheDir/agentlog/agent-log.jsonl`），机器可直接按行读取；与 Web/macOS 统一 Schema。

> 本仓库是 `ai-dev-spec` 日志体系的 Android 实现，以 **git submodule** 形式挂入 `logging-framework/android/AgentLog`。契约真源见 [`docs/INTERFACE.md`](docs/INTERFACE.md)，线格式真源见 [`docs/schema/log-event.schema.json`](docs/schema/log-event.schema.json)。

## 架构

```
业务代码 ──► AgentLog.info/warn/error  ─┐
自动埋点(生命周期/网络/崩溃/卡顿) ──────┤
                                        ├─► Logger（emit/child/setLevel/redact，纯 JVM 核心）
                                        │        │
                                        │        └─► 脱敏（Redactor，logger 层强制）+ snake_case 序列化
                                        │                 │
                                        └────────────────►► FileSink → agent-log.jsonl（JSONL，主消费源）
```

模块划分（对齐语言中立约定）：

- **`agentlog-core`**（纯 Kotlin/JVM，**零第三方依赖**）：实现 `INTERFACE.md` 契约与全部逻辑（`LogEvent` / `Level` / `Redactor` / `Session` / `EventType` / `Logger` / `LoggingConfig` / 最小化 `Json`）。可在无 Android SDK 环境编译与测试（含 `SchemaTests`）。
- **`agentlog-android`**（AGP 库）：以 `api` 依赖 `agentlog-core`，提供 Android 平台胶水（`AgentLogAndroid.bootstrap`、生命周期→`screen_view`、未捕获异常→`crash`、主线程卡顿→`hang`、OkHttp→`network_request` + `traceparent` 注入）。`api` 使 core 的公开类型（`AgentLog`/`Level`/`LoggingConfig` 等）对消费者（示例 App、业务 App）传递可见。
- **`example`**（Android Application，**仅本地/演示，不发布**）：示例 App，演示 `bootstrap` + 四种自动埋点（screen_view 自动、crash/hang/network 按钮触发），用于手测自动埋点行为。

## 设计理念

| 原则 | 落地 |
|---|---|
| 统一、跨端一致 | 线格式遵循 Schema v1（[`docs/schema/log-event.schema.json`](docs/schema/log-event.schema.json)），与 Web/macOS 逐字相同 |
| 轻量、本地优先 | 本地 JSONL 文件，无后端依赖 |
| 隐私兜底 | 日志层强制脱敏，默认不记网络 body |
| 业务零改造 | 薄封装 `AgentLog`，一处初始化，自动埋点默认全开 |

## 接入规范（业务 App）

### 1. 一处 bootstrap（Application.onCreate 最早处）

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentLogAndroid.bootstrap(
            context = this,
            LoggingConfig(
                service = "com.yourapp",
                subsystem = "com.yourapp.agent",
                env = if (BuildConfig.DEBUG) "dev" else "prod",
                minLevel = Level.INFO,
            ),
        )
    }
}
```

### 2. 业务日志（替代裸 `Log.*` / `println`）

```kotlin
AgentLog.info("user opened settings", category = "settings")
AgentLog.error("save failed", traceId = "trace_abc", payload = mapOf("reason" to "disk full"))
val child = AgentLog.child(mapOf("userId" to "u_123"))
child.debug("scoped log")
```

### 3. 网络自动埋点（把拦截器加入 OkHttpClient）

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(AgentNetworkInterceptor()) // 注入 traceparent + 记 network_request
    .build()
```

### 4. 按需关闭自动埋点

```kotlin
val config = LoggingConfig(
    service = "com.yourapp",
    autoInstrumentation = setOf(AutoInstrumentation.NETWORK, AutoInstrumentation.CRASH), // 仅保留网络+崩溃
)
AgentLogAndroid.bootstrap(context = this, config = config)
```

### 5. 不提交日志文件

`agentlog-android/.gitignore` 已忽略 `*.agent-log.jsonl`；`local.properties`（SDK 路径，机器相关）也请忽略。

## 自动埋点范围（默认全开）

| 信号 | 机制 | event_type | 默认 |
|---|---|---|---|
| 页面浏览 | `Application.ActivityLifecycleCallbacks.onActivityResumed` | `screen_view` | ON |
| 未捕获异常 | `Thread.setDefaultUncaughtExceptionHandler` | `crash` | ON |
| 主线程卡顿 | 后台 watchdog 线程 + 主线程心跳（>250ms） | `hang` | ON |
| 网络请求 | OkHttp `AgentNetworkInterceptor`（注入串联 `traceparent`） | `network_request` | ON（**不记 body**，只记 method/host/path/status/耗时/error） |

> UI 交互（`ui_interaction`）对齐 macOS用运行时 swizzle，Android 无对应机制；如需可后续通过 Accessibility / 触摸监听补充。本期聚焦上述四类。

## 示例 App（手测自动埋点）

`example/` 是一个可独立运行的 Android 演示 App，用于**手测自动埋点**行为（CI 仅验证其可编译为 APK，运行时行为需在真机/模拟器验证）：

1. 安装并打开 `example`，日志落盘于 `Context.cacheDir/agentlog/agent-log.jsonl`；点「Show log path」可查看路径，用 `adb pull` 取出分析。
2. 进入页面即触发 `screen_view`（自动，无需代码）。
3. 「Network request」：经 `AgentNetworkInterceptor` 发起请求，记录 `network_request` 并注入 `traceparent`。
4. 「Simulate main-thread hang」：主线程阻塞 700ms，触发 `hang` 事件（>250ms 阈值）。
5. 「Trigger crash」：子线程抛未捕获异常，由崩溃处理器记录 `crash` 事件（进程随后退出，重启后查日志）。
6. 「Log Info/Warn/Error」与「Log with child context」演示业务手动日志与 `child` 上下文绑定。

构建示例 APK：`ANDROID_HOME=~/Library/Android/sdk ./gradlew :example:assembleDebug`（产物在 `example/build/outputs/apk/debug/`）。

## 日志 Schema（面向 Agent）

线格式遵循 **Schema v1**（真源：[`docs/schema/log-event.schema.json`](docs/schema/log-event.schema.json)）。Kotlin 内部用 camelCase，但 `LogEvent` **只在序列化边界**映射为 snake_case；顶层字段集封闭，业务字段一律进 `payload`。

每行一条 JSON（`agent-log.jsonl`）：

```json
{"ts":"2026-11-02T13:37:21.123Z","level":"info","service":"com.yourapp",
 "subsystem":"com.yourapp.agent","category":"settings","event_type":"screen_view",
 "session_id":"...","trace_id":"trace_abc","agent_auto":true,
 "message":"screen_view MainActivity","env":"dev",
 "payload":{"activity":"MainActivity","package":"com.yourapp"}}
```

| 字段 | 说明 | 线格式 |
|---|---|---|
| `ts` | ISO8601 时间戳 | 必填 |
| `level` | trace/debug/info/**warn**/error/critical（`warning` 仅读取兼容，禁止写出） | 必填 |
| `service` | App 标识 | 必填 |
| `subsystem` | 约定以 `.agent` 结尾，便于过滤 | 可选 |
| `category` | 业务分类 | 可选 |
| `event_type` | screen_view/ui_interaction/network_request/crash/hang/log | 必填 |
| `session_id` | 一次启动稳定标识 | 必填 |
| `trace_id` | 跨端/跨调用串联（网络自动注入） | 可选 |
| `agent_auto` | true=自动埋点 | 必填 |
| `message` | 人可读摘要（已脱敏） | 必填 |
| `env` | dev/staging/prod | 可选 |
| `payload` | 事件专属上下文（已脱敏） | 可选 |

## 脱敏（C6 强制）

在 logger 层（而非调用方）强制脱敏。默认脱敏键（各端可加不可减）：`password`、`token`、`authorization`、`cookie`、`secret`，并覆盖 `api_key`、`access_token`、`auth_token` 等带分隔符变体。此外对**字符串值**再做正则兜底（匹配 `key=value` 形式）。网络事件默认只记 `host/path/status/耗时/error`，不记 query 敏感参数与 body。

## 构建与验证

仓库是**单一 Gradle 根工程**（`settings.gradle.kts` 含 `:agentlog-core` + `:agentlog-android` + `:example`），根 wrapper 一处构建：

| 命令 | 说明 | 需 Android SDK |
|---|---|---|
| `./gradlew :agentlog-core:test` | 核心 16 测试（含对齐 Swift 的 `SchemaTests`）+ jar | 否（配置按需，不配置 Android 模块） |
| `./gradlew :agentlog-android:build` | AAR 产出 + Android lint（含 core 编译） | 是（`ANDROID_HOME` 指向 SDK） |
| `./gradlew ktlintCheck` | 全模块 ktlint 静态检查（已接入各模块 `build.gradle.kts`） | 否（纯 JVM 检查） |
| `./gradlew :example:assembleDebug` | 示例 App APK 冒烟构建（验证库集成可编译） | 是 |
| `./gradlew build` | 一次构建全部模块（core 测试 + android AAR + example APK） | 是 |

`agentlog-core` 另保留**独立 wrapper**（`agentlog-core/gradlew`），可在完全无 Android SDK 的环境单独 `./gradlew test ktlintCheck`，用于不关心 Android 的纯 JVM 校验（即 ai-dev-spec gate-runner 的运行方式）。

连续集成见 [`.github/workflows/ci.yml`](.github/workflows/ci.yml)：`core` job（JDK17，无 SDK，跑核心测试 + ktlint）+ `android` job（JDK17 + Android SDK，跑 AGP build + 示例 APK + 全模块 ktlint）。

> 本地首次依赖下载走本机透明代理（等价于 `proxy_on` 后的状态）；CI（GitHub Actions）直连公网，无需代理。

## 目录

```
agent-log-android/
├── settings.gradle.kts          # 根工程：include :agentlog-core + :agentlog-android
├── build.gradle.kts             # 根工程（模块各自持有构建逻辑）
├── gradle.properties            # JVM/Android 配置 + 配置按需
├── gradlew / gradlew.bat        # 根 wrapper（Gradle 8.11，兼容 AGP 8.7）
├── docs/
│   ├── INTERFACE.md             # 语言中立契约（方法面）
│   └── schema/
│       ├── log-event.schema.json  # Schema v1 线格式（字段集封闭、snake_case）
│       └── example.json           # 示例行
├── agentlog-core/               # 纯 Kotlin/JVM，零第三方依赖，可独立单元测试
│   ├── .toolchain.json          # 声明 adapter: "kotlin"（供 ai-dev-spec gate-runner）
│   ├── gradlew / gradlew.bat    # 独立 wrapper（无 SDK 也可构建/测试）
│   └── src/{main,test}/kotlin/com/scaffold/agentlog/...
├── agentlog-android/            # AGP 库，以 api 依赖 core（传递暴露核心公共类型）
│   └── src/main/kotlin/com/scaffold/agentlog/android/...
│       ├── AgentLogAndroid.kt     # bootstrap + 生命周期/崩溃/卡顿注册
│       ├── AgentNetworkInterceptor.kt  # OkHttp 拦截器 + traceparent 注入
│       └── HangMonitor.kt         # 主线程卡顿 watchdog
└── example/                     # 演示 App（手测自动埋点，不发布）
    ├── build.gradle.kts         # com.android.application + ktlint
    └── src/main/.../{DemoApp,MainActivity}.kt + 布局/清单
```

## License

[MIT](LICENSE)
