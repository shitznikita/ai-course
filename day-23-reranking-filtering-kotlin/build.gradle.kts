plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "ru.ai.course"
version = "1.0.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
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

    val trustStore = providers.gradleProperty("elizaTrustStore").orNull
    val trustStorePassword = providers.gradleProperty("elizaTrustStorePassword").orNull ?: "changeit"
    if (trustStore != null) {
        jvmArgs(
            "-Djavax.net.ssl.trustStore=$trustStore",
            "-Djavax.net.ssl.trustStorePassword=$trustStorePassword",
        )
    }
}
