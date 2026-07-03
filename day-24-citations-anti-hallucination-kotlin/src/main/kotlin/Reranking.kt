class QueryRewriter(private val config: AppConfig) {
    fun rewrite(question: String): QueryRewrite {
        if (config.queryRewriteMode != "local") {
            return QueryRewrite(question, question, emptyList())
        }

        val hints = linkedSetOf<String>()
        dayMentions(question).forEach { day -> hints += "day-$day" }

        val lower = question.lowercase()
        if (hasAny(lower, "запуст", "run", "offline", "demo", "команд", "скрипт", "script")) {
            hints += listOf("scripts", "--args", "fixture-demo", "README.md")
        }
        if (hasAny(lower, "metadata", "метадан", "chunk", "чанк", "source", "title", "section", "chunk_id")) {
            hints += listOf("metadata", "source", "title", "section", "chunk_id", "Models.kt")
        }
        if (hasAny(lower, "fixed", "structured", "chunking", "чанкинг", "overlap")) {
            hints += listOf("fixed chunking", "structured chunking", "overlap", "day-21")
        }
        if (hasAny(lower, "runtime", "generated", "commit", "коммит", "коммитить", ".gitignore")) {
            hints += listOf(".gitignore", "runtime", "index", "reports", "generated")
        }
        if (hasAny(lower, "mcp", "orchestration", "оркестр")) {
            hints += listOf("MCP", "orchestration", "source-mcp", "window-mcp", "brief-mcp", "storage-mcp")
        }
        if (hasAny(lower, "endpoint", "model", "модель", "по умолчанию", "default")) {
            hints += listOf("LLM_API_URL", "LLM_MODEL", "README.md", "AGENTS.md")
        }
        if (lower.contains("readme") && hasAny(lower, "day", "день", "folder", "папк")) {
            hints += listOf("AGENTS.md", "goal", "stack", "setup", "run commands", "expected output")
        }
        if (hasAny(lower, "high-level", "sdk", "rest", "httpclient", "java.net.http")) {
            hints += listOf("direct REST", "java.net.http.HttpClient", "high-level LLM SDKs")
        }
        if (hasAny(lower, "context", "контекст", "overflow", "переполнение")) {
            hints += listOf("APP_CONTEXT_LIMIT_TOKENS", "overflow", "file-dry-run", "day-08")
        }

        val rewritten = (listOf(question) + hints).joinToString(" ").trim()
        return QueryRewrite(question, rewritten, hints.toList())
    }

    private fun dayMentions(text: String): List<String> {
        val regex = Regex("""(?i)\bday\s*[- ]?(\d{1,2})\b|\bдень\s*(\d{1,2})\b|\b(\d{1,2})\s*день\b""")
        return regex.findAll(text)
            .mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotBlank() } }
            .map { it.padStart(2, '0') }
            .distinct()
            .toList()
    }
}

class HeuristicReranker(private val config: AppConfig) {
    fun rerank(question: String, rewrite: QueryRewrite, candidates: List<SearchResult>): RerankedRetrieval {
        check(config.rerankMode == "heuristic") {
            "Only RERANK_MODE=heuristic is implemented for Day 24."
        }

        val scored = candidates
            .map { result ->
                val lexicalOverlap = lexicalOverlap(rewrite.rewritten, result.chunk)
                val metadataBoost = metadataBoost(question, rewrite.rewritten, result.chunk)
                val baseScore = result.score.coerceIn(0.0, 1.0)
                val rerankScore = (baseScore * 0.45 + lexicalOverlap * 0.35 + metadataBoost).coerceAtMost(2.0)
                RerankedChunk(
                    result = result,
                    rerankScore = rerankScore,
                    lexicalOverlap = lexicalOverlap,
                    metadataBoost = metadataBoost,
                    passedThreshold = rerankScore >= config.rerankMinScore,
                )
            }
            .sortedByDescending { it.rerankScore }

        val passed = scored.filter { it.passedThreshold }
        val selected = if (passed.isNotEmpty()) {
            passed.take(config.rerankTopKAfter)
        } else {
            scored.take(1).map {
                it.copy(passedThreshold = false, fallback = true)
            }
        }

        return RerankedRetrieval(
            question = question,
            rewrite = rewrite,
            before = candidates,
            selected = selected,
            filteredOutCount = (candidates.size - passed.size).coerceAtLeast(0),
            lowConfidence = passed.isEmpty(),
        )
    }

    private fun lexicalOverlap(query: String, chunk: IndexedChunk): Double {
        val queryTokens = Tokenizer.tokens(query)
            .filter { it.length > 2 }
            .distinct()
        if (queryTokens.isEmpty()) return 0.0

        val searchable = listOf(
            chunk.metadata.source,
            chunk.metadata.title,
            chunk.metadata.section,
            chunk.text,
        ).joinToString(" ").lowercase()

        val hits = queryTokens.count { token -> searchable.contains(token) }
        return hits.toDouble() / queryTokens.size
    }

    private fun metadataBoost(question: String, rewritten: String, chunk: IndexedChunk): Double {
        val query = "$question $rewritten".lowercase()
        val source = chunk.metadata.source.lowercase()
        val title = chunk.metadata.title.lowercase()
        val section = chunk.metadata.section.lowercase()
        val text = chunk.text.lowercase()
        var boost = 0.0

        Regex("""day-(\d{2})""").findAll(rewritten.lowercase()).forEach { match ->
            val day = "day-${match.groupValues[1]}"
            if (source.contains(day)) boost += 0.24
        }
        if (hasAny(query, "runtime", "generated", "коммит", ".gitignore") && source == ".gitignore") boost += 0.36
        if (query.contains("readme") && (source.endsWith("readme.md") || source == "agents.md")) boost += 0.16
        if (hasAny(query, "script", "scripts", "скрипт", "запуст", "fixture-demo") &&
            (source.contains("readme.md") || text.contains("scripts/") || text.contains("--args"))
        ) {
            boost += 0.18
        }
        if (hasAny(query, "metadata", "метадан", "chunk_id", "source", "section")) {
            if (source.contains("models.kt")) boost += 0.55
            if (source.contains("readme.md")) boost += 0.24
            if (text.contains("chunk_id")) boost += 0.18
        }
        if (hasAny(query, "fixed", "structured", "chunking", "чанкинг") && source.contains("day-21-document-indexing")) {
            boost += 0.22
        }
        if (hasAny(query, "endpoint", "модель", "model", "по умолчанию", "llm_api_url") &&
            (source == "readme.md" || source == "agents.md")
        ) {
            boost += 0.20
        }
        if (hasAny(query, "mcp", "orchestration", "source-mcp", "storage-mcp") && source.contains("day-20-mcp-orchestration")) {
            boost += 0.22
        }
        if (hasAny(query, "overflow", "переполнение", "app_context_limit_tokens") && source.contains("day-08-token-accounting")) {
            boost += 0.22
        }
        if (hasAny(query, "high-level", "sdk", "direct rest", "httpclient") &&
            (source == "agents.md" || source.contains("course-continuity"))
        ) {
            boost += 0.20
        }
        if (hasAny(query, "goal", "stack", "setup", "run commands", "expected output") && source == "agents.md") {
            boost += 0.35
        }
        if (title.contains("readme") || section.contains("цель") || section.contains("запуск")) boost += 0.03

        return boost.coerceAtMost(0.9)
    }
}

private fun hasAny(text: String, vararg needles: String): Boolean =
    needles.any { text.contains(it, ignoreCase = true) }
