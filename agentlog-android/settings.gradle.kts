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

// 复用核心模块（纯 JVM），避免重复实现 Schema v1 契约逻辑
includeBuild("../agentlog-core")

rootProject.name = "agentlog-android"
