plugins {
    id("com.android.library") version "8.7.0"
    kotlin("android") version "2.0.21"
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
    // 复用核心模块（纯 JVM）：通过 settings.gradle.kts 的 includeBuild 引入
    implementation("com.scaffold:agentlog-core:0.1.0")
    // 网络自动埋点依赖 OkHttp 拦截器（业务普遍使用；仅 compile 时需要其 API）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
