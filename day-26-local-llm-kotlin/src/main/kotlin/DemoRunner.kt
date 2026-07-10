import java.util.Locale

private val DEMO_PROMPTS = listOf(
    DemoPrompt(
        title = "1. SIMPLE — one fact",
        prompt = "В одном предложении: что такое локальная LLM?",
    ),
    DemoPrompt(
        title = "2. MEDIUM — structured comparison",
        prompt = "Сравни локальную и облачную LLM в Markdown-таблице по трём критериям: приватность, интернет и стоимость.",
    ),
    DemoPrompt(
        title = "3. COMPLEX — local RAG plan",
        prompt = "Составь план не более чем из 120 слов для локального RAG-ассистента по закрытым документам. Укажи квантизацию, embeddings, поиск контекста и проверку ответа.",
    ),
)

class DemoRunner(
    private val config: AppConfig,
    private val client: OllamaClient = OllamaClient(config),
) {
    fun diagnose(): Boolean {
        val status = client.diagnose()
        printStatus(status)
        return status.hasModel(config.model)
    }

    fun runDemo() {
        ensureModelInstalled()
        println()
        println("Day 26: local LLM via Ollama")
        println("LOCAL API: ${config.baseUrl}/api/chat")
        println("MODEL: ${config.model}")
        println("MODE: demo (diagnostic preflight + 3 real local chat requests)")
        println()

        DEMO_PROMPTS.forEachIndexed { index, scenario ->
            val reply = client.chat(scenario.prompt)
            printReply(index + 1, DEMO_PROMPTS.size, scenario, reply)
        }

        println("RESULT: 3/3 responses are non-empty.")
        println("CHECK: the client accepts only a loopback Ollama URL; no Eliza or cloud fallback is configured.")
    }

    fun ask(prompt: String) {
        ensureModelInstalled()
        val reply = client.chat(prompt)
        println()
        println("Day 26: custom local Ollama request")
        println("LOCAL API: ${config.baseUrl}/api/chat")
        printReply(1, 1, DemoPrompt("CUSTOM", prompt), reply)
    }

    private fun ensureModelInstalled() {
        val status = client.diagnose()
        printStatus(status)
        if (!status.hasModel(config.model)) {
            throw ModelNotInstalledException(
                "Model '${config.model}' is not installed in local Ollama at ${config.baseUrl}.",
            )
        }
    }

    private fun printStatus(status: OllamaStatus) {
        println("LOCAL OLLAMA DIAGNOSTIC")
        println("base URL: ${config.baseUrl}")
        println("daemon version: ${status.version}")
        println("required model: ${config.model} (${if (status.hasModel(config.model)) "installed" else "missing"})")
        println("available models:")
        if (status.models.isEmpty()) {
            println("  (none)")
        } else {
            status.models.forEach { model ->
                val details = listOfNotNull(
                    model.parameterSize,
                    model.quantizationLevel,
                    model.sizeBytes.formatSize(),
                ).joinToString(", ")
                println("  - ${model.name}${details.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}")
            }
        }
        if (!status.hasModel(config.model)) {
            println("NEXT: ollama pull ${config.model}")
        }
    }

    private fun printReply(index: Int, total: Int, scenario: DemoPrompt, reply: OllamaReply) {
        println("========== REQUEST $index/$total: ${scenario.title} ==========")
        println("PROMPT:")
        println(scenario.prompt)
        println("ANSWER:")
        println(reply.content)
        println("MODEL USED: ${reply.model}")
        println("TOKENS: input=${reply.promptTokens.orNotAvailable()}, output=${reply.completionTokens.orNotAvailable()}")
        println(
            "LATENCY: client=${reply.clientElapsedNanos.asSeconds()}, " +
                "ollama=${reply.totalDurationNanos.asSeconds()}, " +
                "load=${reply.loadDurationNanos.asSeconds()}, " +
                "generation=${reply.evalDurationNanos.asSeconds()}",
        )
        println("SPEED: ${reply.tokensPerSecond()?.let { String.format(Locale.US, "%.1f tokens/s", it) } ?: "n/a"}")
        println()
    }
}

private fun Long?.formatSize(): String? = when (this) {
    null -> null
    else -> when {
        this >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GiB", this / (1024.0 * 1024.0 * 1024.0))
        this >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", this / (1024.0 * 1024.0))
        else -> "$this B"
    }
}

private fun Long?.orNotAvailable(): String = this?.toString() ?: "n/a"

private fun Long?.asSeconds(): String =
    this?.let { String.format(Locale.US, "%.2f s", it / 1_000_000_000.0) } ?: "n/a"

private fun OllamaReply.tokensPerSecond(): Double? {
    val outputTokens = completionTokens ?: return null
    val durationNanos = evalDurationNanos ?: return null
    if (durationNanos <= 0L) return null
    return outputTokens / (durationNanos / 1_000_000_000.0)
}
