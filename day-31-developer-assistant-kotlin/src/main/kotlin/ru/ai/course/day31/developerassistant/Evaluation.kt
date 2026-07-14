package ru.ai.course.day31.developerassistant

import kotlinx.serialization.decodeFromString
import java.nio.file.Path
import kotlin.io.path.readText

class Evaluation(
    private val config: AppConfig,
    private val embeddings: EmbeddingClient = EmbeddingFactory.create(config),
) {
    fun run(): EvaluationReport {
        val index = RagIndexManager(config, embeddings = embeddings).ensureIndex()
        val retriever = Retriever(config, embeddings)
        val questions = AppJson.tolerant.decodeFromString<List<ControlQuestion>>(
            Path.of("eval/control-questions.json").readText(),
        )
        val manifestSources = index.manifest.map { it.source }.toSet()
        val requiredSources = config.allowedDocuments.toSet()
        val manifestCase = EvaluationCaseResult(
            id = "corpus-manifest",
            passed = manifestSources.containsAll(requiredSources),
            lowConfidence = false,
            retrievedSources = manifestSources.sorted(),
            missingSources = (requiredSources - manifestSources).sorted(),
            missingTerms = emptyList(),
        )
        val questionCases = questions.map { control ->
            val retrieval = retriever.retrieve(index, control.question)
            val retrievedSources = retrieval.hits.map { it.chunk.metadata.source }.distinct()
            val searchable = retrieval.hits.joinToString("\n") { hit ->
                "${hit.chunk.metadata.source}\n${hit.chunk.metadata.section}\n${hit.chunk.text}"
            }.lowercase()
            val missingSources = control.expectedSources.filterNot(retrievedSources::contains)
            val sourceHit = missingSources.isEmpty()
            val missingTerms = control.expectedTerms.filterNot { term -> searchable.contains(term.lowercase()) }
            val passed = if (control.expectUnknown) {
                retrieval.lowConfidence
            } else {
                !retrieval.lowConfidence && sourceHit && missingTerms.isEmpty()
            }
            EvaluationCaseResult(
                id = control.id,
                passed = passed,
                lowConfidence = retrieval.lowConfidence,
                retrievedSources = retrievedSources,
                missingSources = missingSources,
                missingTerms = missingTerms,
            )
        }
        val cases = listOf(manifestCase) + questionCases
        return EvaluationReport(total = cases.size, passed = cases.count(EvaluationCaseResult::passed), cases = cases)
    }
}
