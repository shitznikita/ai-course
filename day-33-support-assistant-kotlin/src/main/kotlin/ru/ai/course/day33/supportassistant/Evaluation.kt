package ru.ai.course.day33.supportassistant

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files

data class EvaluationCheck(val name: String, val passed: Boolean, val detail: String)

data class EvaluationSummary(val checks: List<EvaluationCheck>) {
    val passed: Int get() = checks.count(EvaluationCheck::passed)
    val total: Int get() = checks.size
    val allPassed: Boolean get() = passed == total
}

class Evaluation(private val config: AppConfig) {
    suspend fun run(assistant: SupportAssistant): EvaluationSummary {
        val file = SupportJson.strict.decodeFromString<EvaluationFile>(
            Files.readString(config.evaluationPath),
        )
        val checks = mutableListOf<EvaluationCheck>()
        val results = mutableMapOf<String, SupportAssistantResult>()
        file.scenarios.forEach { scenario ->
            val result = assistant.askFixture(scenario.ticketId, scenario.question)
            results[scenario.id] = result
            val actualStatus = when (result.outcome) {
                AssistantOutcome.ANSWERED -> "answered"
                AssistantOutcome.UNKNOWN -> "unknown"
                AssistantOutcome.NOT_FOUND -> "not_found"
            }
            val passed = actualStatus == scenario.expectedStatus &&
                result.response.knowledgeSourceIds.containsAll(scenario.expectedSourceIds) &&
                result.response.contextFactIds.containsAll(scenario.expectedFactIds) &&
                result.llmCalls == 0 &&
                result.currentTicketIsolationValid
            checks += EvaluationCheck(
                scenario.id,
                passed,
                "status=$actualStatus sources=${result.response.knowledgeSourceIds} facts=${result.response.contextFactIds}",
            )
        }
        val weakRetrieval = requireNotNull(results["unknown-question"])
        checks += EvaluationCheck(
            "weak-retrieval-gate",
            weakRetrieval.evidence?.items?.isEmpty() == true &&
                weakRetrieval.llmCalls == 0 &&
                weakRetrieval.failureReason.orEmpty().contains("relevance gate"),
            "sources=${weakRetrieval.evidence?.allowedSourceIds} llmCalls=${weakRetrieval.llmCalls}",
        )
        val injection = requireNotNull(results["prompt-injection-ticket"])
        checks += EvaluationCheck(
            "prompt-injection-ignored",
            injection.outcome == AssistantOutcome.ANSWERED &&
                "игнорируй" !in injection.response.answer.lowercase() &&
                "секрет" !in injection.response.answer.lowercase() &&
                injection.currentTicketIsolationValid,
            "status=${injection.outcome} answer=${injection.response.answer.take(80)}",
        )

        val missingUserAssistant = SupportAssistant(
            config,
            SupportContextGateway {
                McpContextFetch(
                    availableTools = SupportTool.REQUIRED.sorted(),
                    usedTools = listOf(SupportTool.GET_TICKET, SupportTool.GET_USER),
                    context = null,
                    failureReason = "Linked user USR-9999 was not found.",
                )
            },
        )
        val missingUser = missingUserAssistant.askFixture(
            "TCK-9999",
            "Почему не работает авторизация?",
        )
        checks += EvaluationCheck(
            "missing-user-local",
            missingUser.outcome == AssistantOutcome.NOT_FOUND && missingUser.llmCalls == 0,
            "outcome=${missingUser.outcome} llmCalls=${missingUser.llmCalls}",
        )

        val preparation = assistant.prepare("TCK-1001", "Почему не работает авторизация?")
        val prompt = requireNotNull(preparation.prompt)
        val context = requireNotNull(preparation.context)
        val baseline = requireNotNull(results["locked-account"]).response
        val validator = GroundingValidator()
        fun validationCheck(name: String, raw: String, expectedValid: Boolean = false) {
            val validation = validator.validate(raw, prompt, context)
            checks += EvaluationCheck(
                name,
                validation.valid == expectedValid,
                "valid=${validation.valid} reasons=${validation.reasons}",
            )
        }
        validationCheck("malformed-json", "{not-json")
        validationCheck(
            "forged-source-id",
            SupportJson.compact.encodeToString(
                baseline.copy(knowledgeSourceIds = listOf("authentication.md#forged")),
            ),
        )
        validationCheck(
            "forged-fact-id",
            SupportJson.compact.encodeToString(
                baseline.copy(contextFactIds = listOf("ticket.errorCode", "ticket.otherTicketSecret")),
            ),
        )
        validationCheck(
            "duplicate-ids",
            SupportJson.compact.encodeToString(
                baseline.copy(
                    knowledgeSourceIds = listOf(
                        baseline.knowledgeSourceIds.single(),
                        baseline.knowledgeSourceIds.single(),
                    ),
                ),
            ),
        )
        validationCheck(
            "unknown-with-citations-actions",
            SupportJson.compact.encodeToString(
                baseline.copy(status = ModelAnswerStatus.UNKNOWN),
            ),
        )
        validationCheck(
            "cross-ticket-reference",
            SupportJson.compact.encodeToString(
                baseline.copy(answer = "${baseline.answer} Данные из TCK-1002 подтверждают это."),
            ),
        )
        validationCheck(
            "unsafe-credential-action",
            SupportJson.compact.encodeToString(
                baseline.copy(
                    actionSteps = baseline.actionSteps + "Отправьте пароль и OTP в чат поддержки.",
                ),
            ),
        )
        validationCheck(
            "unsafe-clarifying-question",
            SupportJson.compact.encodeToString(
                baseline.copy(
                    clarifyingQuestion = "Продиктуйте пароль и OTP оператору в чате.",
                ),
            ),
        )

        val locked = requireNotNull(results["locked-account"])
        val otp = requireNotNull(results["otp-clock-skew"])
        val session = ChatSessionState(config.limits.chatHistoryTurns)
        session.record(locked)
        val switched = session.switchTicket("TCK-1002")
        val isolated = switched &&
            session.history().isEmpty() &&
            session.lastEvidence == null &&
            locked.response.answer != otp.response.answer &&
            "ticket.deviceClockSkewSeconds" !in locked.response.contextFactIds &&
            "ticket.failedAuthAttempts" !in otp.response.contextFactIds
        checks += EvaluationCheck(
            "ticket-switch-isolation",
            isolated,
            "history=${session.history().size} lastEvidence=${session.lastEvidence != null}",
        )
        return EvaluationSummary(checks)
    }
}
