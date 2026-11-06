plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

ktlint {
    verbose.set(true)
    // 不强行改换行符；CI/本地统一以 LF 为准，但避免不同 OS 的 CR 差异导致误报。
    ignoreFailures.set(false)
}

group = "com.scaffold"
version = "0.1.0"

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

tasks.compileKotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
