plugins {
    id("com.android.library") version "8.7.0"
    kotlin("android") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

ktlint {
    verbose.set(true)
    ignoreFailures.set(false)
}

android {
    namespace = "com.scaffold.agentlog"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // 复用核心模块（纯 JVM，实现 Schema v1 契约逻辑）。
    // 用 api 而非 implementation：android 库公共 API（bootstrap(LoggingConfig)、AgentNetworkInterceptor 等）
    // 直接暴露 core 的公开类型（AgentLog/Level/LoggingConfig/...），故需对消费者（示例 App、业务 App）传递可见。
    api(project(":agentlog-core"))
    // 网络自动埋点依赖 OkHttp 拦截器（业务普遍使用；仅 compile 时需要其 API）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
