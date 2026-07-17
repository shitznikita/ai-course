package ru.ai.course.day31.developerassistant

class EvidencePackBuilder {
    fun build(retrieval: RetrievalResult, maxTokens: Int): EvidencePack {
        require(maxTokens >= 0) { "Evidence maxTokens must not be negative." }
        if (maxTokens == 0 || retrieval.hits.isEmpty()) return emptyPack(maxTokens, retrieval.hits.isNotEmpty())

        val items = mutableListOf<EvidenceItem>()
        for (hit in retrieval.hits) {
            val safeText = sanitizePromptData(hit.chunk.text)
            val fullItem = item(hit, safeText, textTruncated = false)
            val fullRendered = render(items + fullItem)
            if (TextTools.approxTokens(fullRendered) <= maxTokens) {
                items += fullItem
                continue
            }

            val fitted = fitItem(hit, safeText, items, maxTokens)
            if (fitted != null) items += fitted
            break
        }
        val rendered = render(items)
        return EvidencePack(
            items = items.toList(),
            renderedDocumentation = rendered,
            approxTokens = TextTools.approxTokens(rendered),
            maxTokens = maxTokens,
            retrievalTruncated = items.size < retrieval.hits.size || items.any(EvidenceItem::textTruncated),
        )
    }

    private fun fitItem(
        hit: RetrievalHit,
        safeText: String,
        existing: List<EvidenceItem>,
        maxTokens: Int,
    ): EvidenceItem? {
        var low = 0
        var high = safeText.length
        var best: EvidenceItem? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidateText = truncatedText(safeText, middle)
            val candidate = item(hit, candidateText, textTruncated = middle < safeText.length)
            val tokens = TextTools.approxTokens(render(existing + candidate))
            if (tokens <= maxTokens) {
                if (candidateText.any(Char::isLetterOrDigit)) best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun item(hit: RetrievalHit, text: String, textTruncated: Boolean): EvidenceItem {
        val block = buildString {
            appendLine("[sourceId=${hit.chunk.metadata.chunkId}]")
            appendLine("source=${sanitizePromptData(hit.chunk.metadata.source)}")
            appendLine("section=${sanitizePromptData(hit.chunk.metadata.section)}")
            append(text)
        }
        return EvidenceItem(
            hit = hit,
            text = text,
            renderedBlock = block,
            textTruncated = textTruncated,
        )
    }

    private fun truncatedText(text: String, length: Int): String {
        if (length >= text.length) return text
        if (length <= 0) return ""
        val prefix = text.take(length).trimEnd()
        return if (prefix.isEmpty()) "" else "$prefix\n[chunk text truncated by evidence budget]"
    }

    private fun render(items: List<EvidenceItem>): String =
        items.joinToString("\n\n---\n\n", transform = EvidenceItem::renderedBlock)
            .ifBlank { "(no project documentation selected)" }

    private fun emptyPack(maxTokens: Int, truncated: Boolean): EvidencePack {
        val rendered = "(no project documentation selected)"
        return EvidencePack(
            items = emptyList(),
            renderedDocumentation = rendered,
            approxTokens = 0,
            maxTokens = maxTokens,
            retrievalTruncated = truncated,
        )
    }
}

class McpEvidenceBuilder {
    fun build(context: McpProjectContext, fileByteBudget: Int): McpEvidence {
        require(fileByteBudget >= 0) { "MCP file byte budget must not be negative." }
        val files = context.files?.let { raw ->
            val included = mutableListOf<String>()
            for (path in raw.files) {
                val candidate = included + path
                if (TextTools.utf8Bytes(renderFileLines(candidate)) > fileByteBudget) break
                included += path
            }
            McpFileEvidence(
                prefix = raw.prefix,
                files = included.toList(),
                serverReturnedCount = raw.files.size,
                serverTruncated = raw.truncated,
                boundedIncludedCount = included.size,
                byteBudgetTruncated = included.size < raw.files.size,
            )
        }
        return McpEvidence(
            availableTools = context.availableTools.toList(),
            usedTools = context.usedTools.toList(),
            branch = context.branch,
            files = files,
        )
    }

    private fun renderFileLines(files: List<String>): String =
        files.joinToString("\n") { "- ${sanitizePromptData(it)}" }
}

object GroundingRequirementsFactory {
    fun forQuestion(question: String): GroundingRequirements {
        val normalized = question.lowercase()
        val branchRequired = DeveloperAssistant.asksAboutBranch(question)
        val filesRequired = listOf(
            "tracked file",
            "tracked files",
            "tracked-file",
            "tracked-files",
            "file list",
            "list files",
            "show files",
            "which files",
            "what files",
            "список файлов",
            "какие файлы",
            "покажи файлы",
            "показать файлы",
            "файлы проекта",
            "tracked-файл",
        ).any(normalized::contains)
        val structureIntent = listOf(
            "структур",
            "устро",
            "архитект",
            "модул",
            "добавить",
        ).any(normalized::contains)
        val documentationRequired = structureIntent || listOf(
            "readme",
            "docs",
            "документ",
            "rag",
            "mcp tool",
            "команд",
            "запуск",
            "run",
            "privacy",
            "приват",
            "облак",
        ).any(normalized::contains) || (!branchRequired && !filesRequired)
        return GroundingRequirements(
            documentationRequired = documentationRequired,
            branchRequired = branchRequired,
            filesRequired = filesRequired,
            fetchFiles = filesRequired || structureIntent,
        )
    }
}

private fun sanitizePromptData(text: String): String =
    text
        .replace("[sourceId=", "[documentSourceId=")
        .replace("<PROJECT_DOCUMENTATION_UNTRUSTED>", "<document-tag-redacted>")
        .replace("</PROJECT_DOCUMENTATION_UNTRUSTED>", "</document-tag-redacted>")
        .replace("<MCP_PROJECT_CONTEXT_UNTRUSTED>", "<mcp-tag-redacted>")
        .replace("</MCP_PROJECT_CONTEXT_UNTRUSTED>", "</mcp-tag-redacted>")
