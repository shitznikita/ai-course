package ru.ai.course.day32.codereview

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowSecurityTest {
    @Test
    fun `privileged workflow executes only immutable default base revision`() {
        val workflow = Files.readString(repositoryRoot().resolve(".github/workflows/ai-code-review.yml"))

        assertTrue("pull_request_target:" in workflow)
        assertTrue("base.ref == github.event.repository.default_branch" in workflow)
        assertTrue("base.repo.full_name == github.repository" in workflow)
        assertTrue("head.repo.full_name == github.repository" in workflow)
        assertTrue("ref: \${{ github.event.pull_request.base.sha }}" in workflow)
        assertTrue("persist-credentials: false" in workflow)
        assertEquals(1, Regex("""uses:\s+actions/checkout@""").findAll(workflow).count())
        assertTrue("contents: read" in workflow)
        assertTrue("pull-requests: write" in workflow)
        assertFalse("issues: write" in workflow)
        assertTrue("LLM_API_KEY: \${{ secrets.LLM_API_KEY }}" in workflow)
        assertTrue("cancel-in-progress: true" in workflow)
    }

    private fun repositoryRoot(): Path {
        val fromModule = Path.of("..").toAbsolutePath().normalize()
        return if (Files.isDirectory(fromModule.resolve(".github"))) {
            fromModule
        } else {
            Path.of(".").toAbsolutePath().normalize()
        }
    }
}
