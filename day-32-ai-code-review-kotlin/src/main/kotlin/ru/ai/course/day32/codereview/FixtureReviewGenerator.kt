package ru.ai.course.day32.codereview

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path

class FixtureAnswerGenerator : ReviewGenerator {
    override fun generate(prompt: PromptPack): LlmReply =
        error("FixtureAnswerGenerator requires a batch-aware adapter.")

    fun generate(input: TransmittedReviewInput): LlmReply {
        val anchors = input.files
            .flatMap { file -> file.parsedDiff.addedLines.sorted().map { file.path to it } }
        val evidenceIds = input.evidenceItems.map { it.chunk.id }.take(2)
        require(anchors.isNotEmpty()) { "Fixture batch has no added-line anchors." }
        require(evidenceIds.isNotEmpty()) { "Fixture batch has no RAG evidence." }
        val categories = listOf(
            FindingCategory.BUG to FindingSeverity.HIGH,
            FindingCategory.ARCHITECTURE to FindingSeverity.MEDIUM,
            FindingCategory.RECOMMENDATION to FindingSeverity.LOW,
        )
        val findings = categories.mapIndexedNotNull { index, (category, severity) ->
            val anchor = anchors.getOrNull(index) ?: return@mapIndexedNotNull null
            ModelFinding(
                category = category,
                severity = severity,
                path = anchor.first,
                line = anchor.second,
                title = when (category) {
                    FindingCategory.BUG -> "Небезопасное разыменование может завершить запрос"
                    FindingCategory.ARCHITECTURE -> "HTTP-слой напрямую зависит от хранилища"
                    FindingCategory.RECOMMENDATION -> "Секрет не должен попадать в диагностический вывод"
                },
                detail = when (category) {
                    FindingCategory.BUG -> "Оператор !! превращает отсутствующее значение в исключение вместо контролируемой ошибки."
                    FindingCategory.ARCHITECTURE -> "Новая зависимость смешивает транспортную и доменную ответственность."
                    FindingCategory.RECOMMENDATION -> "Даже маскированный секрет лучше не передавать в строку диагностического ответа."
                },
                recommendation = when (category) {
                    FindingCategory.BUG -> "Вернуть типизированный not-found результат и обработать его до формирования ответа."
                    FindingCategory.ARCHITECTURE -> "Вынести обращение к хранилищу за интерфейс сервиса приложения."
                    FindingCategory.RECOMMENDATION -> "Логировать только безопасный идентификатор операции без значения секрета."
                },
                evidenceIds = evidenceIds,
            )
        }
        return LlmReply(
            model = "fixture-reviewer",
            content = ReviewJson.strict.encodeToString(ModelReview(findings)),
        )
    }
}

data class FixtureContext(
    val snapshot: PullRequestSnapshot,
    val corpus: LoadedCorpus,
)

object FixtureReviewGenerator {
    fun create(repositoryRoot: Path): FixtureContext {
        val fixtureRoot = repositoryRoot.resolve("day-32-ai-code-review-kotlin/fixtures/repository")
        val diffPath = repositoryRoot.resolve("day-32-ai-code-review-kotlin/fixtures/demo.diff")
        val patch = if (Files.isRegularFile(diffPath)) {
            DiffParser().patchesByPath(Files.readString(diffPath))["src/ReviewTarget.kt"]
                ?: embeddedPatch
        } else {
            embeddedPatch
        }
        val documents = if (Files.isDirectory(fixtureRoot)) {
            Files.walk(fixtureRoot).use { paths ->
                paths.filter(Files::isRegularFile).sorted().map { path ->
                    val relative = fixtureRoot.relativize(path).toString().replace('\\', '/')
                    CorpusDocument(
                        path = relative,
                        content = Files.readString(path),
                        category = if (relative.endsWith(".md")) {
                            CorpusCategory.DOCUMENTATION
                        } else {
                            CorpusCategory.CODE
                        },
                        priority = if (relative.endsWith(".md")) 0 else 1,
                    )
                }.toList()
            }
        } else {
            embeddedDocuments
        }
        val file = ChangedFile(
            path = "src/ReviewTarget.kt",
            status = "modified",
            sha = "b".repeat(40),
            additions = 3,
            deletions = 1,
            changes = 4,
            patch = patch,
            content = embeddedChangedContent,
        )
        val metadata = PullRequestMetadata(
            repository = "example/ai-course",
            number = 32,
            title = "Fixture: code review risks",
            baseSha = "a".repeat(40),
            headSha = "b".repeat(40),
            draft = false,
            fromFork = false,
            changedFiles = 1,
        )
        val bytes = documents.sumOf { TextTools.utf8Bytes(it.content).toLong() }
        return FixtureContext(
            snapshot = PullRequestSnapshot(metadata, listOf(file), patch, fullDiffTruncated = false),
            corpus = LoadedCorpus(
                documents,
                CorpusMetrics(
                    trackedFiles = documents.size,
                    includedFiles = documents.size,
                    includedBytes = bytes,
                    skippedFiles = 0,
                    truncated = false,
                    skippedReasons = emptyMap(),
                ),
            ),
        )
    }

    fun run(config: AppConfig): ReviewResult {
        val context = create(config.repositoryRoot)
        val fixture = FixtureAnswerGenerator()
        val pipeline = ReviewPipeline(
            config = config,
            generator = ReviewGenerator { error("Batch-aware fixture path must be used.") },
        )
        val prepared = pipeline.prepare(context.snapshot, context.corpus)
        val validator = ReviewValidator()
        val reviewedPaths = linkedSetOf<String>()
        val findings = prepared.flatMap { item ->
            if (item.input.files.isEmpty() || item.input.evidenceItems.isEmpty()) return@flatMap emptyList()
            val reply = fixture.generate(item.input)
            val result = validator.validate(reply.content, item.input)
            check(result.valid) { result.errors.joinToString() }
            reviewedPaths += item.input.reviewedPaths
            result.findings
        }
        val merged = validator.merge(findings)
        val coverage = ReviewCoverage(
            reportedChangedFiles = 1,
            fetchedChangedFiles = 1,
            reviewedFiles = reviewedPaths.size,
            binaryFiles = 0,
            patchTruncatedFiles = 0,
            contentTruncatedFiles = 0,
            diffEndpointAvailable = true,
            corpusMetrics = context.corpus.metrics,
        )
        val markdown = MarkdownReviewRenderer().render(
            context.snapshot.metadata,
            merged,
            coverage,
            "fixture-reviewer",
        )
        return ReviewResult(context.snapshot.metadata, merged, coverage, "fixture-reviewer", markdown)
    }

    private val embeddedPatch = """
        @@ -5,2 +5,4 @@ class ReviewTarget(
         fun load(id: String): String {
        -    return repository.find(id) ?: "missing"
        +    val entity = repository.find(id)!!
        +    val raw = repository.connection().query(entity)
        +    return "loaded=${'$'}raw secret=${'$'}apiKey"
         }
    """.trimIndent()

    private val embeddedChangedContent = """
        class ReviewTarget(private val repository: Repository, private val apiKey: String) {
            fun load(id: String): String {
                val entity = repository.find(id)!!
                val raw = repository.connection().query(entity)
                return "loaded=${'$'}raw secret=${'$'}apiKey"
            }
        }
    """.trimIndent()

    private val embeddedDocuments = listOf(
        CorpusDocument(
            "README.md",
            "# Architecture\nHTTP handlers call application services; repositories stay behind domain ports.\n",
            CorpusCategory.DOCUMENTATION,
            0,
        ),
        CorpusDocument(
            "src/Repository.kt",
            "interface Repository { fun find(id: String): Entity? }\n",
            CorpusCategory.CODE,
            1,
        ),
    )
}
