pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "agent-log-android"

// agentlog-core：纯 Kotlin/JVM 核心（零第三方依赖，可在无 Android SDK 下编译/测试）
// agentlog-android：AGP 库，编译期依赖 core
// example：示例 App（手测自动埋点），依赖 agentlog-android
include(":agentlog-core", ":agentlog-android", ":example")
