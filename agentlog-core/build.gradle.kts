plugins {
    kotlin("jvm") version "2.0.21"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}
