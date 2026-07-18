package ru.ai.course.day34.fileassistant

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

object TestSupport {
    fun repositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        repeat(8) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: error("Cannot locate repository root.")
        }
        error("Cannot locate repository root.")
    }

    fun moduleRoot(): Path = repositoryRoot().resolve("day-34-project-file-assistant-kotlin")

    fun fixtureCopy(prefix: String = "project-"): Path {
        val parent = moduleRoot().resolve("build/test-runtime")
        Files.createDirectories(parent)
        val target = Files.createTempDirectory(parent, prefix)
        val source = moduleRoot().resolve("fixtures/sample-project")
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != source) Files.createDirectory(target.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
        return target
    }

    fun config(port: Int = 0): AppConfig = AppConfig(mcpPort = port)
}
