package ru.ai.course.day34.fileassistant

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

class DemoWorkspace(private val moduleRoot: Path = Path.of("").toAbsolutePath().normalize()) {
    private val fixtureRoot = moduleRoot.resolve("fixtures/sample-project")
    private val runtimeRoot = moduleRoot.resolve("runtime")

    fun reset(name: String): Path {
        require(name.matches(Regex("[a-z0-9][a-z0-9-]{0,60}"))) { "Invalid demo workspace name." }
        require(Files.isDirectory(fixtureRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Fixture project is missing: $fixtureRoot"
        }
        require(!Files.isSymbolicLink(fixtureRoot)) { "Fixture root must not be a symlink." }
        Files.createDirectories(runtimeRoot)
        require(!Files.isSymbolicLink(runtimeRoot)) { "Runtime root must not be a symlink." }
        val target = runtimeRoot.resolve(name).normalize()
        require(target.parent == runtimeRoot) { "Demo workspace escaped runtime root." }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) deleteTree(target)
        Files.createDirectory(target)
        copyTree(fixtureRoot, target)
        return target
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!Files.isSymbolicLink(dir)) { "Fixture symlink directories are forbidden." }
                if (dir != source) Files.createDirectory(target.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(attrs.isRegularFile && !Files.isSymbolicLink(file)) {
                    "Fixture must contain regular non-symlink files only."
                }
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteTree(target: Path) {
        require(target.startsWith(runtimeRoot) && target != runtimeRoot) { "Refusing to delete outside demo runtime." }
        require(!Files.isSymbolicLink(target)) { "Refusing to reset a symlink demo target." }
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!Files.isSymbolicLink(dir)) { "Refusing to traverse a symlink in demo runtime." }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
