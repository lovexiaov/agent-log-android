plugins {
    id("com.android.application") version "8.7.0"
    kotlin("android") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

ktlint {
    verbose.set(true)
    ignoreFailures.set(false)
}

android {
    namespace = "cn.lovexiaov.agent.log.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.lovexiaov.agent.log.example"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // 示例 App 仅用于本地手测自动埋点；放宽 lint 避免演示性告警阻断构建。
    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    // 演示如何接入 AgentLog 库（自动埋点 + 结构化 JSONL 落盘）
    implementation(project(":agentlog-android"))
    // 网络自动埋点：把 AgentNetworkInterceptor 加入 OkHttpClient（见 MainActivity）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
