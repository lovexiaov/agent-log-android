plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

ktlint {
    verbose.set(true)
    // 不强行改换行符；CI/本地统一以 LF 为准，但避免不同 OS 的 CR 差异导致误报。
    ignoreFailures.set(false)
}

group = "cn.lovexiaov.agent.log"
version = "1.4.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    // 让测试输出可见，便于在 CI / 本地排查
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// 全局钉 JDK 17：Kotlin 2.0.x 编译器无法在 JDK 25 上运行（Internal compiler error），
// 而 JavaCompile 默认跟随运行 Gradle 的 JDK，会引发 JVM-target 不一致。
// 用 toolchain 让 Java/Kotlin 两侧统一到 17，且不依赖运行 Gradle 的那个 JDK 版本。
kotlin {
    jvmToolchain(17)
}

tasks.compileKotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
