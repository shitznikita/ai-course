package ru.ai.course.day31.developerassistant

object ConsoleRenderer {
    fun printCorpus(index: RagIndex) {
        println("RAG CORPUS")
        println("documents: ${index.manifest.size}")
        index.manifest.forEach { entry ->
            println("- ${entry.source} sha256=${entry.contentSha256.take(12)} bytes=${entry.bytes}")
        }
        println("chunks: ${index.chunks.size}")
        println("embeddings: ${index.embeddingBackend}/${index.embeddingModel} (${index.embeddingDimensions} dimensions)")
        println()
    }

    fun printMcp(context: McpProjectContext) {
        println("MCP TOOLS: ${context.availableTools.joinToString(", ")}")
        println("MCP TOOLS USED: ${context.usedTools.joinToString(", ")}")
        println("TYPED MCP CLAIMS:")
        println("PROJECT BRANCH: ${context.branch.displayName}")
        context.files?.let { result ->
            println("PROJECT FILES: ${result.files.size}${if (result.truncated) "+" else ""}")
            result.files.forEach { println("- $it") }
        } ?: println("PROJECT FILES: 0")
        println()
    }

    fun printRun(run: AssistantRun, includePrompt: Boolean = false) {
        println("USER COMMAND: /help ${run.question}")
        run.preflightRefusalReason?.let { println("PRE-RETRIEVAL REFUSAL: $it") }
        println("MCP TOOLS USED: ${run.mcp.usedTools.joinToString(", ")}")
        run.mcp.files?.let { files ->
            println(
                "MCP FILES: serverReturned=${files.serverReturnedCount}, " +
                    "serverTruncated=${files.serverTruncated}, boundedIncluded=${files.boundedIncludedCount}, " +
                    "byteBudgetTruncated=${files.byteBudgetTruncated}",
            )
        }
        println("RETRIEVED CANDIDATES: ${run.retrieval.hits.size} chunks")
        println(
            "EVIDENCE PACK: ${run.evidence.items.size} chunks, " +
                "${run.evidence.approxTokens}/${run.evidence.maxTokens} tokens, " +
                "truncated=${run.evidence.retrievalTruncated}",
        )
        run.evidence.items.forEachIndexed { index, item ->
            val hit = item.hit
            println(
                "${index + 1}. score=${"%.3f".format(hit.score)} " +
                    "${hit.chunk.metadata.source}#${hit.chunk.metadata.section} " +
                    "[${item.sourceId}] textTruncated=${item.textTruncated}",
            )
        }
        println(
            "PROMPT BUDGET: approxTokens=${run.prompt.approxTokens}, " +
                "utf8Bytes=${run.prompt.utf8Bytes}/${run.prompt.maxBytes}, " +
                "tokenEnvelope=${run.prompt.maxTokens}",
        )
        println("TYPED MCP CLAIMS:")
        println("PROJECT BRANCH: ${run.answer.projectBranch ?: "none"}")
        println("PROJECT FILES: ${run.answer.projectFiles.size}")
        run.answer.projectFiles.forEach { println("- $it") }
        println("ANSWER: ${run.answer.answer}")
        println("STATUS: ${run.answer.status}")
        println("SOURCES:")
        if (run.answer.sourceIds.isEmpty()) {
            println("- none")
        } else {
            val hitsById = run.evidence.items.associateBy(EvidenceItem::sourceId)
            run.answer.sourceIds.forEach { id ->
                val metadata = hitsById[id]?.hit?.chunk?.metadata
                if (metadata == null) println("- invalid:$id") else println("- ${metadata.source}#${metadata.section} [$id]")
            }
        }
        val check = when {
            !run.validation.valid -> "invalid answer: ${run.validation.errors.joinToString("; ")}"
            run.answer.status == "unknown" -> "unknown gate passed; source IDs are empty; unsupported answer was not generated"
            else -> {
                val evidenceKinds = buildList {
                    if (run.requirements.documentationRequired) add("rendered RAG sources")
                    if (run.requirements.branchRequired) add("MCP branch")
                    if (run.requirements.filesRequired) add("MCP tracked files")
                }
                "answer grounded; ${evidenceKinds.joinToString(" + ")} valid"
            }
        }
        println("CHECK: $check")
        if (run.fixture) println("MODE: deterministic offline response; Ollama was not called")
        if (includePrompt) {
            println()
            println("PROMPT PREVIEW")
            println(run.prompt.preview)
        }
    }

    fun printEvaluation(report: EvaluationReport) {
        println("EVAL DRY RUN")
        report.cases.forEach { result ->
            println("${if (result.passed) "PASS" else "FAIL"} ${result.id}")
            println("  lowConfidence=${result.lowConfidence}")
            println("  retrieved=${result.retrievedSources.joinToString()}")
            if (result.missingSources.isNotEmpty()) println("  missingSources=${result.missingSources.joinToString()}")
            if (result.missingTerms.isNotEmpty()) println("  missingTerms=${result.missingTerms.joinToString()}")
        }
        println("RESULT: ${report.passed}/${report.total} passed")
        println("CHECK: README + docs + API schema retrieval and unknown gate evaluated without Ollama")
    }
}
