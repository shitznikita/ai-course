plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "ru.ai.course"
version = "1.0.0"

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
    implementation("io.ktor:ktor-client-cio:3.4.3")
    implementation("io.ktor:ktor-server-cio:3.4.3")
    implementation("io.ktor:ktor-server-cors:3.4.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("net.java.dev.jna:jna:5.17.0")
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
    standardInput = System.`in`
    jvmArgs("-Dkotlin-logging.logStartupMessage=false")

    val trustStore = providers.gradleProperty("elizaTrustStore").orNull
    val trustStorePassword = providers.gradleProperty("elizaTrustStorePassword").orNull ?: "changeit"
    if (trustStore != null) {
        jvmArgs(
            "-Djavax.net.ssl.trustStore=$trustStore",
            "-Djavax.net.ssl.trustStorePassword=$trustStorePassword",
        )
    }
}
