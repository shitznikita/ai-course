package ru.ai.course.day33.supportassistant

import java.nio.file.Path

object SupportDataPolicy {
    private val ticketIdPattern = Regex("""TCK-[0-9]{4,8}""")
    private val userIdPattern = Regex("""USR-[0-9]{4,8}""")
    private val codePattern = Regex("""[A-Z][A-Z0-9_]{1,63}""")
    private val localePattern = Regex("""[a-z]{2}-[A-Z]{2}""")

    fun requireValid(data: SupportDataFile): SupportDataFile {
        require(data.synthetic) { "Support fixture must declare synthetic=true." }
        require(data.users.size in 1..100) { "Support fixture must contain 1..100 users." }
        require(data.tickets.size in 1..500) { "Support fixture must contain 1..500 tickets." }
        require(data.users.map(SupportUser::id).distinct().size == data.users.size) {
            "Support user IDs must be unique."
        }
        require(data.tickets.map(SupportTicket::id).distinct().size == data.tickets.size) {
            "Support ticket IDs must be unique."
        }

        val userIds = data.users.map(SupportUser::id).toSet()
        data.users.forEach(::validateUser)
        data.tickets.forEach { ticket ->
            validateTicket(ticket)
            require(ticket.userId in userIds) { "Ticket ${ticket.id} references missing user ${ticket.userId}." }
        }
        return data
    }

    fun requireTicketId(value: String): String {
        val id = value.trim()
        require(ticketIdPattern.matches(id)) { "ticketId must match TCK-<digits>." }
        return id
    }

    fun requireUserId(value: String): String {
        val id = value.trim()
        require(userIdPattern.matches(id)) { "userId must match USR-<digits>." }
        return id
    }

    fun requireCurrentReferences(
        value: String,
        ticketId: String,
        userId: String,
        label: String,
    ) {
        val foreignTicket = ticketReferences(value).firstOrNull { it != ticketId.uppercase() }
        val foreignUser = userReferences(value).firstOrNull { it != userId.uppercase() }
        require(foreignTicket == null && foreignUser == null) {
            "$label references a different ticket or user."
        }
    }

    fun ticketReferences(value: String): Set<String> = TICKET_REFERENCE.findAll(value)
        .map { "TCK-${it.groupValues[1]}" }
        .toSet()

    fun userReferences(value: String): Set<String> = USER_REFERENCE.findAll(value)
        .map { "USR-${it.groupValues[1]}" }
        .toSet()

    fun facts(context: SupportContext): List<ContextFact> = listOf(
        ContextFact("ticket.id", "Ticket ID", context.ticket.id),
        ContextFact("ticket.userId", "Linked user ID", context.ticket.userId),
        ContextFact("ticket.category", "Category", context.ticket.category),
        ContextFact("ticket.productArea", "Product area", context.ticket.productArea),
        ContextFact("ticket.status", "Ticket status", context.ticket.status),
        ContextFact("ticket.priority", "Priority", context.ticket.priority),
        ContextFact("ticket.errorCode", "Error code", context.ticket.errorCode),
        ContextFact("ticket.failedAuthAttempts", "Failed auth attempts", context.ticket.failedAuthAttempts.toString()),
        ContextFact(
            "ticket.deviceClockSkewSeconds",
            "Device clock skew seconds",
            context.ticket.deviceClockSkewSeconds.toString(),
        ),
        ContextFact("user.id", "User ID", context.user.id),
        ContextFact("user.plan", "Plan", context.user.plan),
        ContextFact("user.accountState", "Account state", context.user.accountState),
        ContextFact("user.locale", "Locale", context.user.locale),
    )

    fun sanitized(ticket: SupportTicket, user: SupportUser): SupportContext {
        validateTicket(ticket)
        validateUser(user)
        require(ticket.userId == user.id) { "Ticket ${ticket.id} is not linked to user ${user.id}." }
        val context = SupportContext(ticket, user, emptyList())
        return context.copy(facts = facts(context))
    }

    fun sanitizeDataMarker(value: String): String = value
        .replace("<<<", "‹‹‹")
        .replace(">>>", "›››")

    fun requireCloudSafe(
        fixturePath: Path,
        knowledgeDirectory: Path,
        maxChunkChars: Int,
        prompt: PromptPack,
    ) {
        val input = prompt.transmittedInput
        val reviewedFixture = TextTools.readUtf8Bounded(
            fixturePath,
            1_000_000,
            "Reviewed synthetic fixture",
        )
        val fingerprint = TextTools.sha256(reviewedFixture)
        require(fingerprint == REVIEWED_FIXTURE_SHA256) {
            "Live cloud mode requires the exact reviewed synthetic Day 33 fixture."
        }

        val repository = JsonSupportDataRepository(fixturePath)
        val ticket = requireNotNull(repository.ticket(input.ticketId)) {
            "Current ticket is absent from the reviewed synthetic fixture."
        }
        val user = requireNotNull(repository.user(ticket.userId)) {
            "Current user is absent from the reviewed synthetic fixture."
        }
        val expected = sanitized(ticket, user)
        require(input.linkedUserId == expected.user.id) { "Cloud context user does not match reviewed fixture." }
        require(input.contextFacts == expected.facts) { "Cloud context facts do not match reviewed fixture." }
        require(input.untrustedTicketSummary == sanitizeDataMarker(expected.ticket.summary)) {
            "Cloud ticket summary does not match reviewed fixture."
        }
        require(input.recentHistory.all { it.ticketId == expected.ticket.id }) {
            "Cloud history belongs to another ticket."
        }
        require(prompt.allowedFactIds == expected.facts.map(ContextFact::id).toSet()) {
            "Cloud fact allowlist does not match reviewed fixture."
        }
        require(
            knowledgeDirectory.normalize() ==
                fixturePath.parent.parent.resolve("knowledge").normalize()
        ) {
            "Live cloud mode requires the reviewed Day 33 knowledge directory."
        }
        requireReviewedKnowledge(knowledgeDirectory, maxChunkChars, prompt)
        requireNoSensitiveValue(prompt.system + "\n" + prompt.user)
    }

    private fun requireReviewedKnowledge(
        knowledgeDirectory: Path,
        maxChunkChars: Int,
        prompt: PromptPack,
    ) {
        REVIEWED_KNOWLEDGE_SHA256.forEach { (fileName, fingerprint) ->
            val path = knowledgeDirectory.resolve(fileName)
            val reviewedDocument = TextTools.readUtf8Bounded(
                path,
                100_000,
                "Reviewed knowledge document",
            )
            require(TextTools.sha256(reviewedDocument) == fingerprint) {
                "Live cloud mode requires exact reviewed Day 33 knowledge documents."
            }
        }
        val reviewedChunks = KnowledgeDocumentLoader(knowledgeDirectory)
            .load()
            .flatMap(StructuredChunker(maxChunkChars)::chunk)
            .associateBy(KnowledgeChunk::sourceId)
        require(prompt.allowedSourceIds == prompt.transmittedInput.evidence.map { it.sourceId }.toSet()) {
            "Cloud source allowlist does not match transmitted evidence."
        }
        prompt.transmittedInput.evidence.forEach { evidence ->
            val reviewed = requireNotNull(reviewedChunks[evidence.sourceId]) {
                "Cloud evidence source is not in the reviewed knowledge corpus."
            }
            require(
                evidence.heading == reviewed.heading &&
                    evidence.text == sanitizeDataMarker(reviewed.text)
            ) {
                "Cloud evidence does not match the reviewed knowledge corpus."
            }
        }
    }

    private fun requireNoSensitiveValue(value: String) {
        val sensitive = SENSITIVE_PATTERNS.any { it.containsMatchIn(value) }
        require(!sensitive) {
            "Cloud input contains a sensitive-looking value; the request was stopped locally."
        }
    }

    private fun validateUser(user: SupportUser) {
        requireUserId(user.id)
        TextTools.bounded(user.displayName, 80, "displayName")
        require(codePattern.matches(user.plan)) { "User ${user.id} has invalid plan." }
        require(codePattern.matches(user.accountState)) { "User ${user.id} has invalid accountState." }
        require(localePattern.matches(user.locale)) { "User ${user.id} has invalid locale." }
    }

    private fun validateTicket(ticket: SupportTicket) {
        requireTicketId(ticket.id)
        requireUserId(ticket.userId)
        require(codePattern.matches(ticket.category)) { "Ticket ${ticket.id} has invalid category." }
        require(codePattern.matches(ticket.productArea)) { "Ticket ${ticket.id} has invalid productArea." }
        require(codePattern.matches(ticket.status)) { "Ticket ${ticket.id} has invalid status." }
        require(codePattern.matches(ticket.priority)) { "Ticket ${ticket.id} has invalid priority." }
        require(codePattern.matches(ticket.errorCode)) { "Ticket ${ticket.id} has invalid errorCode." }
        TextTools.bounded(ticket.summary, 800, "ticket summary")
        require(ticket.failedAuthAttempts in 0..100) { "Ticket ${ticket.id} has invalid failedAuthAttempts." }
        require(ticket.deviceClockSkewSeconds in -86_400..86_400) {
            "Ticket ${ticket.id} has invalid deviceClockSkewSeconds."
        }
    }

    private const val REVIEWED_FIXTURE_SHA256 =
        "4220e198bde06d530e45fd15bee869ea87436e4ae0e821b5a425786bdf79d89b"

    private val REVIEWED_KNOWLEDGE_SHA256 = mapOf(
        "faq.md" to "ca247cd582da03021b38e9994cda575e13c79bb97aa9672c852ef0d295779aaa",
        "authentication.md" to "3cacf1e8ae0a4dce26e1803503995625416747ac4004dfe08471e21bdb04a51e",
        "billing.md" to "4439a813b170d7996c45eaf4242b4f0cfec8d69b3c7c0d293c211ee3231c4118",
        "escalation.md" to "dd9bd5a561cbe8d3c2dc7322e58c53261c51c85f47f3a801ff5d1124d9ed2b22",
    )

    private val SENSITIVE_PATTERNS = listOf(
        Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b"""),
        Regex("""(?<![\p{L}\d])\+?\d(?:[\s().-]*\d){9,14}(?!\d)"""),
        Regex("""\b(?:\d[ -]*?){13,19}\b"""),
        Regex("""(?<!\d)\d{6,8}(?!\d)"""),
        Regex(
            """(?<![\p{L}\p{N}_])(?:password|пароль|token|токен|api[_ -]?key|secret|секрет)(?![\p{L}\p{N}_])\s*(?:[:=]|is|это)\s*["']?[^\s"',}]{4,}""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """(?<![\p{L}\p{N}_])(?:otp|одноразов(?:ый|ого)?\s+код|cvc|cvv)(?![\p{L}\p{N}_])\s*(?:[:=]|is|это)\s*["']?\d{3,8}(?!\d)""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """(?<![\p{L}\p{N}_])(?:мой|моя|my)\s+(?:password|пароль|token|токен|api[_ -]?key|secret|секрет|otp|одноразов(?:ый|ого)?\s+код|cvc|cvv)(?![\p{L}\p{N}_])\s*(?:(?:[:=]|is|это)\s*)?["']?[^\s"',}]{3,}""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """(?<![\p{L}\p{N}_])(?:password|пароль|token|токен|api[_ -]?key|secret|секрет|otp|cvc|cvv)(?![\p{L}\p{N}_])\s+["']?(?=[^\s"',}]*\d)[^\s"',}]{3,}""",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            """(?<![\p{L}\p{N}_])(?:password|пароль|token|токен|api[_ -]?key|secret|секрет)(?![\p{L}\p{N}_])\s+(?!(?:или|и|не|для|без|никогда|нельзя|нужен|нужны|работает|сбросить|изменить|забыт|забыл|забыла|неверный|просрочен|истек|истёк|требуется)(?![\p{L}\p{N}_]))["']?[^\s"',}.!?]{4,}""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""(?i)\b(?:bearer|oauth)\s+[A-Za-z0-9._~+/=-]{8,}"""),
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"""),
        Regex("""-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"""),
    )
    private val TICKET_REFERENCE = Regex(
        """(?<![\p{L}\p{N}_])(?:TCK|TICKET|ТИКЕТ)(?![\p{L}\p{N}_])[\s\u00A0\-\u2010-\u2015\u2212]*([0-9]{4,8})(?![0-9])""",
        RegexOption.IGNORE_CASE,
    )
    private val USER_REFERENCE = Regex(
        """(?<![\p{L}\p{N}_])(?:USR|USER|ПОЛЬЗОВАТЕЛЬ)(?![\p{L}\p{N}_])[\s\u00A0\-\u2010-\u2015\u2212]*([0-9]{4,8})(?![0-9])""",
        RegexOption.IGNORE_CASE,
    )
}
