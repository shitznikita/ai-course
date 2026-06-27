plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "ru.ai.course"
version = "1.0.0"

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
    implementation("io.ktor:ktor-client-cio:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    jvmArgs("-Dkotlin-logging.logStartupMessage=false")
}
