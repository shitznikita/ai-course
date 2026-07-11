plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "ru.ai.course"
version = "1.0.0"

dependencies {
    implementation("io.ktor:ktor-server-cio:3.4.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.3")
    implementation("io.ktor:ktor-server-status-pages:3.4.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:3.4.3")
}

application {
    mainClass.set("MainKt")
}

distributions {
    main {
        contents {
            from("knowledge") { into("knowledge") }
            from("catalog") { into("catalog") }
            from("eval") { into("eval") }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}
