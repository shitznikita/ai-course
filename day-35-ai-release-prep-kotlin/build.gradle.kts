plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "ru.ai.course"
version = "1.0.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit5"))
}

application {
    mainClass.set("ru.ai.course.day35.releaseprep.MainKt")
}

distributions {
    main {
        contents {
            from("fixtures") { into("fixtures") }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}
