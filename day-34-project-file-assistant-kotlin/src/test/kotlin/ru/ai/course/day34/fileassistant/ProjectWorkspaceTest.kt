package ru.ai.course.day34.fileassistant

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectWorkspaceTest {
    @Test
    fun `preview requires read and matching sha then keeps changes in overlay`() {
        val root = TestSupport.fixtureCopy("preview-")
        val original = Files.readString(root.resolve("README.md"))
        val workspace = ProjectWorkspace(ProjectFilePolicy(root), WriteMode.PREVIEW)

        assertFailsWith<IllegalStateException> {
            workspace.writeFile("README.md", "$original\nUnsafe.\n", null)
        }
        assertFailsWith<IllegalArgumentException> {
            workspace.readFiles(listOf("README.md"))
        }
        workspace.listFiles(null, 200)
        val read = workspace.readFiles(listOf("README.md")).files.single()
        assertFailsWith<IllegalArgumentException> {
            workspace.writeFile("README.md", "$original\nStale.\n", "0".repeat(64))
        }
        val updated = "$original\nPreview-safe change.\n"
        val written = workspace.writeFile("README.md", updated, read.sha256)

        assertTrue(written.changed)
        assertEquals(original, Files.readString(root.resolve("README.md")))
        assertEquals(updated, workspace.readFiles(listOf("README.md")).files.single().content)
        val diff = workspace.unifiedDiff()
        assertEquals(listOf("README.md"), diff.changedPaths)
        assertTrue("--- a/README.md" in diff.diff)
        assertTrue("+Preview-safe change." in diff.diff)
    }

    @Test
    fun `apply detects disk race and atomically updates an approved file`() {
        val root = TestSupport.fixtureCopy("apply-")
        val workspace = ProjectWorkspace(ProjectFilePolicy(root), WriteMode.APPLY)
        workspace.listFiles(null, 200)
        val read = workspace.readFiles(listOf("docs/api.md")).files.single()
        Files.writeString(root.resolve("docs/api.md"), read.content + "\nExternal edit.\n")

        assertFailsWith<IllegalArgumentException> {
            workspace.writeFile("docs/api.md", read.content + "\nAgent edit.\n", read.sha256)
        }

        val fresh = ProjectWorkspace(ProjectFilePolicy(root), WriteMode.APPLY)
        fresh.listFiles(null, 200)
        val freshRead = fresh.readFiles(listOf("docs/api.md")).files.single()
        fresh.writeFile("docs/api.md", freshRead.content + "\nApplied edit.\n", freshRead.sha256)

        assertTrue("Applied edit." in Files.readString(root.resolve("docs/api.md")))
        assertFalse(Files.list(root.resolve("docs")).use { stream ->
            stream.anyMatch { it.fileName.toString().contains(".day34-") }
        })
    }

    @Test
    fun `apply preserves executable permission when replacing a script`() {
        val root = TestSupport.fixtureCopy("mode-")
        val script = root.resolve("verify.sh")
        Files.writeString(script, "#!/usr/bin/env bash\necho before\n")
        val supported = runCatching {
            Files.setPosixFilePermissions(
                script,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }.isSuccess
        if (!supported) return
        val workspace = ProjectWorkspace(ProjectFilePolicy(root), WriteMode.APPLY)
        workspace.listFiles(null, 200)
        val read = workspace.readFiles(listOf("verify.sh")).files.single()

        workspace.writeFile("verify.sh", "#!/usr/bin/env bash\necho after\n", read.sha256)

        assertTrue(Files.isExecutable(script))
    }

    @Test
    fun `search read write and diff budgets are deterministic`() {
        val root = TestSupport.fixtureCopy("search-")
        val workspace = ProjectWorkspace(ProjectFilePolicy(root), WriteMode.PREVIEW)

        val search = workspace.searchText("LegacyPaymentsApi", null, 100)
        assertEquals(4, search.hits.size)
        assertEquals(3, search.hits.map(SearchHit::path).distinct().size)
        workspace.writeFile("CHANGELOG.md", "# Changelog\n", null)
        val first = workspace.unifiedDiff()
        val second = workspace.unifiedDiff()

        assertEquals(first, second)
        assertTrue("--- /dev/null" in first.diff)
        assertTrue("+++ b/CHANGELOG.md" in first.diff)
        assertEquals(sha256(first.diff), first.sha256)
    }
}
