import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FactMemoryUpdater(
    private val llmClient: LlmClient,
    private val tokenCounter: ApproximateTokenCounter,
    private val mode: String,
) {
    fun update(current: StickyFacts, userMessage: String): Pair<StickyFacts, FactUpdateStats> {
        if (mode.lowercase() == "heuristic") {
            val next = heuristicUpdate(current, userMessage)
            return next to FactUpdateStats(
                promptTokens = tokenCounter.countText(userMessage),
                mode = "heuristic",
                warning = null,
            )
        }

        val prompt = buildPrompt(current, userMessage)
        val messages = listOf(
            ChatMessage(
                "system",
                "Ты обновляешь key-value facts для агента. Верни только JSON без markdown.",
            ),
            ChatMessage("user", prompt),
        )
        val response = llmClient.complete(messages)
        val updated = parseFacts(response.content) ?: heuristicUpdate(current, userMessage)
        val warning = response.warningOrError ?: if (parseFacts(response.content) == null) {
            "Facts update used heuristic fallback because model did not return valid JSON."
        } else {
            null
        }

        return updated to FactUpdateStats(
            promptTokens = response.usage?.promptTokens ?: tokenCounter.countMessages(messages),
            mode = "llm",
            warning = warning,
        )
    }

    private fun buildPrompt(current: StickyFacts, userMessage: String): String {
        return """
        Обнови key-value facts на основе нового сообщения пользователя.
        Сохрани только устойчивые важные факты: цель, ограничения, предпочтения, решения, договоренности.
        Не добавляй догадки. Не пересказывай весь диалог.
        Верни только JSON с теми же ключами:
        userName, goal, audience, platform, constraints, preferences, decisions, risks, timeline, monetization.

        Current facts:
        ${appJson.encodeToString(current)}

        New user message:
        $userMessage
        """.trimIndent()
    }

    private fun parseFacts(text: String): StickyFacts? {
        val jsonText = text.substringAfter("{", missingDelimiterValue = "")
            .substringBeforeLast("}", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { "{$it}" }
            ?: return null

        return runCatching {
            appJson.decodeFromString<StickyFacts>(jsonText)
        }.getOrElse {
            runCatching {
                val obj = appJson.parseToJsonElement(jsonText).jsonObject
                StickyFacts(
                    userName = obj.value("userName"),
                    goal = obj.value("goal"),
                    audience = obj.value("audience"),
                    platform = obj.value("platform"),
                    constraints = obj.value("constraints"),
                    preferences = obj.value("preferences"),
                    decisions = obj.value("decisions"),
                    risks = obj.value("risks"),
                    timeline = obj.value("timeline"),
                    monetization = obj.value("monetization"),
                )
            }.getOrNull()
        }
    }

    private fun JsonObject.value(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun heuristicUpdate(current: StickyFacts, userMessage: String): StickyFacts {
        var facts = current
        val text = userMessage.lowercase()

        if ("меня зовут" in text) facts = facts.copy(userName = "Никита")
        if ("мобильное приложение" in text || "учета личных финансов" in text) {
            facts = facts.copy(goal = "мобильное приложение для учета личных финансов", platform = "mobile")
        }
        if ("целевая аудитория" in text) facts = facts.copy(audience = "люди, которые плохо контролируют расходы")
        if ("без backend" in text || "без бэкенд" in text) facts = facts.copy(constraints = appendFact(facts.constraints, "первый релиз без backend"))
        if ("категории расходов" in text) facts = facts.copy(decisions = appendFact(facts.decisions, "категории расходов"))
        if ("лимиты по категориям" in text) facts = facts.copy(decisions = appendFact(facts.decisions, "лимиты по категориям"))
        if ("интерфейс" in text && "прост" in text) facts = facts.copy(preferences = appendFact(facts.preferences, "очень простой интерфейс"))
        if ("csv" in text) facts = facts.copy(decisions = appendFact(facts.decisions, "экспорт в CSV"))
        if ("пуши" in text && "не нужны" in text) facts = facts.copy(constraints = appendFact(facts.constraints, "push-уведомления не нужны в MVP"))
        if ("подписка" in text) facts = facts.copy(monetization = "подписка позже, не в MVP")
        if ("3 недели" in text) facts = facts.copy(timeline = "MVP за 3 недели")
        if ("риск" in text || "ux" in text) facts = facts.copy(risks = "главный риск - слишком сложный UX")

        return facts
    }

    private fun appendFact(existing: String, newFact: String): String {
        if (existing.isBlank()) return newFact
        if (newFact in existing) return existing
        return "$existing; $newFact"
    }
}
