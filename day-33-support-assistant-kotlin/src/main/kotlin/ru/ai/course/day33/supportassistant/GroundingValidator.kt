package ru.ai.course.day33.supportassistant

import kotlinx.serialization.decodeFromString

class GroundingValidator {
    fun validate(raw: String, prompt: PromptPack, context: SupportContext): GroundingValidation {
        val response = runCatching {
            require(TextTools.utf8Bytes(raw) <= 32_768) { "Model JSON exceeds 32 KB." }
            SupportJson.strict.decodeFromString<SupportModelResponse>(raw)
        }.getOrElse {
            return GroundingValidation(false, null, listOf("malformed or schema-invalid JSON"))
        }
        val reasons = mutableListOf<String>()
        validateText(response, reasons)
        validateIds(response, prompt, reasons)
        validateStatus(response, reasons)
        validateUnsafeInstructions(response, reasons)
        validateCurrentTicket(response, context, reasons)
        validateEvidenceOverlap(response, prompt, reasons)
        return GroundingValidation(reasons.isEmpty(), response.takeIf { reasons.isEmpty() }, reasons)
    }

    private fun validateText(response: SupportModelResponse, reasons: MutableList<String>) {
        if (response.answer.isBlank() || response.answer.length > 1_200) {
            reasons += "answer must contain 1..1200 characters"
        }
        if (response.actionSteps.size > 6) reasons += "too many action steps"
        if (response.actionSteps.any { it.isBlank() || it.length > 240 }) {
            reasons += "action step is blank or too long"
        }
        if (response.clarifyingQuestion?.let { it.isBlank() || it.length > 240 } == true) {
            reasons += "clarifying question is blank or too long"
        }
    }

    private fun validateIds(
        response: SupportModelResponse,
        prompt: PromptPack,
        reasons: MutableList<String>,
    ) {
        if (response.knowledgeSourceIds.distinct().size != response.knowledgeSourceIds.size) {
            reasons += "duplicate knowledge source IDs"
        }
        if (response.contextFactIds.distinct().size != response.contextFactIds.size) {
            reasons += "duplicate context fact IDs"
        }
        if (!prompt.allowedSourceIds.containsAll(response.knowledgeSourceIds)) {
            reasons += "unknown or forged knowledge source ID"
        }
        if (!prompt.allowedFactIds.containsAll(response.contextFactIds)) {
            reasons += "unknown or forged context fact ID"
        }
    }

    private fun validateStatus(response: SupportModelResponse, reasons: MutableList<String>) {
        when (response.status) {
            ModelAnswerStatus.ANSWERED -> {
                if (response.knowledgeSourceIds.isEmpty()) reasons += "answered result has no RAG source"
                if (response.contextFactIds.none { it.startsWith("ticket.") }) {
                    reasons += "answered result has no ticket context fact"
                }
                if (response.actionSteps.isEmpty()) reasons += "answered result has no action steps"
                if (response.clarifyingQuestion != null) {
                    reasons += "answered result must not contain a clarifying question"
                }
            }
            ModelAnswerStatus.UNKNOWN -> {
                if (
                    response.knowledgeSourceIds.isNotEmpty() ||
                    response.contextFactIds.isNotEmpty() ||
                    response.actionSteps.isNotEmpty()
                ) {
                    reasons += "unknown result must not contain citations or actions"
                }
                if (response.clarifyingQuestion == null) {
                    reasons += "unknown result must contain a clarifying question"
                }
            }
        }
    }

    private fun validateCurrentTicket(
        response: SupportModelResponse,
        context: SupportContext,
        reasons: MutableList<String>,
    ) {
        val text = buildString {
            append(response.answer).append('\n')
            response.actionSteps.forEach { append(it).append('\n') }
            append(response.clarifyingQuestion.orEmpty())
        }
        if (SupportDataPolicy.ticketReferences(text).any { it != context.ticket.id.uppercase() }) {
            reasons += "cross-ticket reference"
        }
        if (SupportDataPolicy.userReferences(text).any { it != context.user.id.uppercase() }) {
            reasons += "cross-user reference"
        }
    }

    private fun validateUnsafeInstructions(
        response: SupportModelResponse,
        reasons: MutableList<String>,
    ) {
        val unsafe = (listOf(response.answer) + response.actionSteps + listOfNotNull(response.clarifyingQuestion))
            .map(TextTools::normalize)
            .filter { text -> SENSITIVE_OBJECTS.any { it in text } }
            .any(::hasPositiveTransferInstruction)
        if (unsafe) reasons += "unsafe credential-sharing instruction"
    }

    private fun hasPositiveTransferInstruction(text: String): Boolean =
        TRANSFER_VERB.findAll(text).any { match ->
            val prefix = text.substring(maxOf(0, match.range.first - 32), match.range.first)
            !NEGATED_TRANSFER_PREFIX.containsMatchIn(prefix)
        }

    private fun validateEvidenceOverlap(
        response: SupportModelResponse,
        prompt: PromptPack,
        reasons: MutableList<String>,
    ) {
        if (response.status != ModelAnswerStatus.ANSWERED) return
        val responseTokens = TextTools.tokens(
            response.answer + "\n" + response.actionSteps.joinToString("\n"),
        ).toSet()
        val cited = prompt.transmittedInput.evidence.filter { it.sourceId in response.knowledgeSourceIds }
        val overlaps = cited.any { evidence ->
            val evidenceTokens = TextTools.tokens(evidence.text).toSet()
            responseTokens.intersect(evidenceTokens).size >= 2
        }
        if (!overlaps) reasons += "answer has insufficient overlap with cited evidence"
    }

    companion object {
        private val SENSITIVE_OBJECTS = listOf(
            "парол", "password", "otp", "одноразов", "токен", "token",
            "cvc", "cvv", "номер карт", "card number", "секрет", "secret",
        )
        private val TRANSFER_VERB = Regex(
            """(?:отправ|пришл|сообщ|переда|подел|продикт)\p{L}*|(?:send|shar|provid|tell)\p{L}*""",
            RegexOption.IGNORE_CASE,
        )
        private val NEGATED_TRANSFER_PREFIX = Regex(
            """(?:^|\s)(?:не|not|never|don't|do\s+not|не\s+(?:надо|нужно|следует))\s*$""",
            RegexOption.IGNORE_CASE,
        )

        fun canonicalUnknown(): SupportModelResponse = SupportModelResponse(
            status = ModelAnswerStatus.UNKNOWN,
            answer = "Недостаточно подтверждённых данных, чтобы дать надёжный ответ по текущему тикету.",
            actionSteps = emptyList(),
            knowledgeSourceIds = emptyList(),
            contextFactIds = emptyList(),
            clarifyingQuestion = "Уточните видимый код ошибки или передайте тикет специалисту поддержки.",
        )

        fun canonicalNotFound(ticketId: String): SupportModelResponse = SupportModelResponse(
            status = ModelAnswerStatus.UNKNOWN,
            answer = "Синтетический тикет $ticketId не найден; запрос к LLM не выполнялся.",
            actionSteps = emptyList(),
            knowledgeSourceIds = emptyList(),
            contextFactIds = emptyList(),
            clarifyingQuestion = "Проверьте ID тикета.",
        )
    }
}
