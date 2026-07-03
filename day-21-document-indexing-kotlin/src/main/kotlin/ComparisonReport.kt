import java.time.Instant

class ComparisonReport(private val config: AppConfig) {
    private val sampleQueries = listOf(
        "как устроен MCP orchestration",
        "где хранится persistent context агента",
        "как запустить Eliza через Gradle",
        "что такое chunking и overlap",
    )

    fun build(
        corpus: LoadedCorpus,
        fixed: IndexBuildResult,
        structured: IndexBuildResult,
        embeddingClient: EmbeddingClient,
    ): String {
        val search = VectorSearch(embeddingClient)
        return buildString {
            appendLine("# Day 21 Chunking Comparison")
            appendLine()
            appendLine("Generated: ${Instant.now()}")
            appendLine()
            appendLine("## Corpus")
            appendLine()
            appendLine("- Documents dir: `${corpus.documentsDir}`")
            appendLine("- Loaded documents: ${corpus.documents.size}")
            appendLine("- Approx tokens: ${corpus.approxTokens}")
            appendLine("- Approx pages: ${corpus.approxPages.formatDigits(1)}")
            if (corpus.skipped.isNotEmpty()) {
                appendLine("- Skipped files: ${corpus.skipped.size}")
            }
            appendLine()
            appendLine("## Strategy Summary")
            appendLine()
            appendLine("| Strategy | Documents | Chunks | Min tokens | Avg tokens | Max tokens | Embedding dims | Backend |")
            appendLine("|---|---:|---:|---:|---:|---:|---:|---|")
            appendLine(summaryRow(fixed.index))
            appendLine(summaryRow(structured.index))
            appendLine()
            appendLine("## Sample Retrieval")
            sampleQueries.forEach { query ->
                appendLine()
                appendLine("### Query: $query")
                appendLine()
                appendLine("#### fixed")
                appendResults(search.search(fixed.index, query, config.retrievalTopK))
                appendLine()
                appendLine("#### structured")
                appendResults(search.search(structured.index, query, config.retrievalTopK))
            }
            appendLine()
            appendLine("## Takeaway")
            appendLine()
            appendLine("- `fixed` creates predictable windows and keeps boundaries safe through overlap, but sections are artificial.")
            appendLine("- `structured` keeps headings/files/declarations in metadata, so retrieval output is easier to cite in a RAG answer.")
        }
    }

    private fun summaryRow(index: DocumentIndex): String {
        val tokenSizes = index.chunks.map { it.metadata.approxTokens }
        val min = tokenSizes.minOrNull() ?: 0
        val max = tokenSizes.maxOrNull() ?: 0
        val avg = if (tokenSizes.isEmpty()) 0.0 else tokenSizes.average()
        return "| `${index.strategy}` | ${index.sourceDocumentCount} | ${index.chunkCount} | $min | ${avg.formatDigits(1)} | $max | ${index.embeddingDimensions} | `${index.embeddingBackend}` |"
    }

    private fun StringBuilder.appendResults(results: List<SearchResult>) {
        results.forEachIndexed { index, result ->
            appendLine(
                "${index + 1}. score=${result.score.formatDigits()} " +
                    "`${result.chunk.metadata.source}` / ${result.chunk.metadata.section} " +
                    "(${result.chunk.metadata.approxTokens} tokens)",
            )
        }
    }
}
