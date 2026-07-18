package ru.ai.course.day34.fileassistant

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectFilePolicyTest {
    @Test
    fun `discovery exposes bounded project text and excludes generated secrets binaries and symlinks`() {
        val root = TestSupport.fixtureCopy("policy-")
        Files.createDirectories(root.resolve("build"))
        Files.writeString(root.resolve("build/generated.kt"), "class Generated")
        Files.writeString(root.resolve(".env"), "TOKEN=secret")
        Files.write(root.resolve("binary.md"), byteArrayOf(65, 0, 66))
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(root.resolve("linked.md"), Path.of("README.md"))
        }.isSuccess
        val policy = ProjectFilePolicy(root)

        val discovered = policy.discover(prefix = null, limit = 200)

        assertTrue("README.md" in discovered.files)
        assertTrue(discovered.files.any { it.endsWith("PaymentClient.kt") })
        assertFalse(".env" in discovered.files)
        assertFalse("build/generated.kt" in discovered.files)
        assertFalse("binary.md" in discovered.files)
        if (symlinkCreated) assertFalse("linked.md" in discovered.files)
    }

    @Test
    fun `path policy rejects traversal absolute secret generated and symlink paths`() {
        val root = TestSupport.fixtureCopy("guards-")
        Files.createDirectories(root.resolve("runtime"))
        val policy = ProjectFilePolicy(root)

        assertFailsWith<IllegalArgumentException> { policy.normalizeFile("../outside.md") }
        assertFailsWith<IllegalArgumentException> { policy.normalizeFile(root.resolve("README.md").toString()) }
        assertFailsWith<IllegalArgumentException> { policy.normalizeFile(".env.example") }
        assertFailsWith<IllegalArgumentException> { policy.normalizeFile("runtime/output.md") }
        assertFailsWith<IllegalArgumentException> { policy.normalizeFile("private-key.pem") }

        if (runCatching { Files.createSymbolicLink(root.resolve("linked.md"), Path.of("README.md")) }.isSuccess) {
            assertFailsWith<IllegalArgumentException> { policy.resolveExisting("linked.md") }
            assertFailsWith<IllegalArgumentException> { policy.resolveWriteTarget("linked.md") }
        }
    }

    @Test
    fun `prefix and result limits stay bounded`() {
        val policy = ProjectFilePolicy(TestSupport.fixtureCopy("limit-"))

        val docs = policy.discover("docs", 1)

        assertEquals(listOf("docs/api.md"), docs.files)
        assertFailsWith<IllegalArgumentException> { policy.discover(null, 201) }
        assertFailsWith<IllegalArgumentException> { policy.discover("../", 10) }
    }

    @Test
    fun `late NUL is excluded from discovery and search and rejected by authoritative read`() {
        val root = TestSupport.fixtureCopy("late-nul-")
        val bytes = ByteArray(9_200) { 'a'.code.toByte() }
        "late-nul-sentinel\n".toByteArray().copyInto(bytes)
        bytes[8_700] = 0
        Files.write(root.resolve("late-nul.md"), bytes)
        val policy = ProjectFilePolicy(root)
        val workspace = ProjectWorkspace(policy, WriteMode.PREVIEW)

        assertFalse("late-nul.md" in policy.discover(null, 200).files)
        val search = workspace.searchText("late-nul-sentinel", null, 100)
        assertFalse(search.hits.any { it.path == "late-nul.md" })
        assertFailsWith<IllegalArgumentException> { policy.resolveExisting("late-nul.md") }
        assertFailsWith<IllegalArgumentException> { SecureProjectFiles(policy).read("late-nul.md") }
    }
}
