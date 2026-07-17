package ru.ai.course.day31.developerassistant

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitProjectGatewayTest {
    @Test
    fun `reports branch and only bounded tracked files`() {
        val root = createGitRepository()
        root.resolve("README.md").writeText("tracked")
        root.resolve("docs").createDirectories()
        root.resolve("docs/api.yaml").writeText("openapi: 3.1.0")
        root.resolve(".env").writeText("SECRET=not-tracked")
        git(root, "add", "README.md", "docs/api.yaml")
        git(root, "commit", "-m", "fixture")

        val gateway = GitProjectGateway(root)
        assertEquals("test-branch", gateway.currentBranch().displayName)
        val files = gateway.listTrackedFiles(prefix = null, limit = 1)
        assertEquals(1, files.files.size)
        assertTrue(files.truncated)
        assertFalse(files.files.any { it == ".env" })
        assertEquals(listOf("docs/api.yaml"), gateway.listTrackedFiles("docs", 10).files)

        git(root, "checkout", "--detach")
        val detached = gateway.currentBranch()
        assertTrue(detached.detached)
        assertTrue(detached.displayName.startsWith("detached@"))
        assertTrue(detached.shortSha.orEmpty().isNotBlank())
    }

    @Test
    fun `rejects traversal prefix`() {
        val root = createGitRepository()
        val gateway = GitProjectGateway(root)
        assertFailsWith<IllegalArgumentException> {
            gateway.listTrackedFiles("../outside", 10)
        }
    }

    private fun createGitRepository(): Path {
        val root = createTempDirectory("day31-git-")
        git(root, "init", "-b", "test-branch")
        git(root, "config", "user.email", "test@example.invalid")
        git(root, "config", "user.name", "Day 31 Test")
        return root
    }

    private fun git(root: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
