package ru.ai.course.day31.developerassistant

class Retriever(
    private val config: AppConfig,
    private val embeddings: EmbeddingClient,
) {
    fun retrieve(index: RagIndex, question: String): RetrievalResult {
        require(index.embeddingBackend == embeddings.backend && index.embeddingModel == embeddings.model) {
            "Index embedding provider does not match the active embedding provider; rebuild the index."
        }
        val query = question.trim()
        require(query.isNotEmpty()) { "Question must not be blank." }
        val retrievalQuery = expandQuery(query)
        val queryVector = embeddings.embed(listOf(retrievalQuery)).single()
        require(queryVector.size == index.embeddingDimensions) {
            "Query embedding dimension ${queryVector.size} does not match index dimension ${index.embeddingDimensions}; rebuild the index."
        }

        val scoredCandidates = index.chunks.map { chunk ->
            Candidate(
                chunk = chunk,
                vectorScore = cosineSimilarity(queryVector, chunk.embedding).coerceAtLeast(0.0),
                lexicalScore = lexicalSimilarity(retrievalQuery, chunk),
                metadataBoost = (
                    genericDocumentBoost(chunk.metadata.source) +
                        intentMetadataBoost(query, chunk.metadata)
                    ).coerceAtMost(0.20),
            )
        }
        val vectorCandidates = scoredCandidates
            .sortedWith(compareByDescending<Candidate> { it.vectorScore }.thenBy { it.chunk.metadata.chunkId })
            .take(config.candidateCount)
        val lexicalCandidates = scoredCandidates
            .sortedWith(
                compareByDescending<Candidate> { it.lexicalScore + it.metadataBoost }
                    .thenByDescending { it.vectorScore }
                    .thenBy { it.chunk.metadata.chunkId },
            )
            .take(config.candidateCount)
        val candidates = (vectorCandidates + lexicalCandidates)
            .distinctBy { it.chunk.metadata.chunkId }
            .map(::score)
            .sortedWith(compareByDescending<RetrievalHit> { it.score }.thenBy { it.chunk.metadata.chunkId })
        val hits = candidates.take(config.topK)
        val relevance = hits.firstOrNull()?.score ?: 0.0
        return RetrievalResult(
            question = question,
            hits = hits,
            lowConfidence = hits.isEmpty() || relevance < config.minRelevance,
        )
    }

    private fun score(candidate: Candidate): RetrievalHit {
        return RetrievalHit(
            score = (
                candidate.vectorScore * 0.55 +
                    candidate.lexicalScore * 0.42 +
                    candidate.metadataBoost
                ).coerceAtMost(1.0),
            vectorScore = candidate.vectorScore,
            lexicalScore = candidate.lexicalScore,
            metadataBoost = candidate.metadataBoost,
            chunk = candidate.chunk,
        )
    }

    private fun lexicalSimilarity(question: String, chunk: IndexedChunk): Double {
        val queryTokens = TextTools.tokens(question).filter { it.length >= 2 }.toSet()
        if (queryTokens.isEmpty()) return 0.0
        val searchableTokens = TextTools.tokens(
            listOf(chunk.metadata.source, chunk.metadata.section, chunk.text).joinToString(" "),
        ).toSet()
        return queryTokens.count(searchableTokens::contains).toDouble() / queryTokens.size
    }

    private fun genericDocumentBoost(source: String): Double = when {
        source.equals("README.md", ignoreCase = true) || source.endsWith("/README.md", ignoreCase = true) -> 0.03
        source.startsWith("docs/") -> 0.015
        else -> 0.0
    }

    private fun intentMetadataBoost(question: String, metadata: ChunkMetadata): Double {
        val normalized = question.lowercase()
        val source = metadata.source.lowercase()
        val section = metadata.section.lowercase()
        var boost = 0.0
        if (
            listOf("устро", "структур", "архитект", "модул", "новый день", "new day").any(normalized::contains) &&
            (source == "docs/project-architecture.md" || "gradle modules" in section || "архитект" in section)
        ) {
            boost += 0.14
        }
        if (
            listOf("ветк", "branch", "git").any(normalized::contains) &&
            (source == "docs/developer-assistant-api.yaml" || "mcp" in section)
        ) {
            boost += 0.08
        }
        if (
            listOf("команд", "/help", "/branch", "/files", "cli").any(normalized::contains) &&
            source == "docs/developer-assistant-api.yaml"
        ) {
            boost += 0.16
        }
        if (
            listOf("провер", "offline", "без ollama", "fixture", "eval").any(normalized::contains) &&
            (
                source == "day-31-developer-assistant-kotlin/readme.md" ||
                    "offline demo" in section ||
                    "проверк" in section
                )
        ) {
            boost += 0.14
        }
        if (
            listOf("cloud", "облак", "приват", "privacy").any(normalized::contains) &&
            source == "docs/project-architecture.md"
        ) {
            boost += 0.14
        }
        return boost
    }

    private fun expandQuery(question: String): String {
        val normalized = question.lowercase()
        val hints = buildList {
            if (listOf("устро", "структур", "архитект", "модул", "новый день", "new day").any(normalized::contains)) {
                add("архитектура структура проекта Gradle modules day-модуль settings.gradle.kts")
            }
            if (listOf("ветк", "branch", "git").any(normalized::contains)) {
                add("текущая git ветка current branch git_current_branch")
            }
            if (listOf("документ", "rag", "corpus", "корпус", "allowlist").any(normalized::contains)) {
                add("README docs документация RAG corpus allowlist")
            }
            if (listOf("команд", "/help", "запуск", "run", "провер", "offline", "без ollama").any(normalized::contains)) {
                add("CLI команды /help /branch /files fixture-demo eval-dry-run")
            }
            if (listOf("cloud", "облак", "приват", "privacy", "секрет").any(normalized::contains)) {
                add("loopback local Ollama cloud privacy secrets")
            }
        }
        return (listOf(question) + hints).joinToString("\n")
    }

    private data class Candidate(
        val chunk: IndexedChunk,
        val vectorScore: Double,
        val lexicalScore: Double,
        val metadataBoost: Double,
    )
}
