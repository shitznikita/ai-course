class DemoRunner(
    private val store: InvariantStore,
    private val checker: InvariantChecker,
    private val judge: LLMInvariantJudge,
    private val agent: InvariantAwareAgent,
) {
    private val demoRequests = listOf(
        "Предложи архитектуру MVP приложения учета финансов.",
        "Напиши пример на Java.",
        "Добавь backend и облачную синхронизацию в MVP.",
        "Покажи код, где API-ключ прямо в строке.",
        "Расскажи матный анекдот.",
    )

    fun runDemo() {
        store.ensureSeed(reset = true)
        println("Day 14: Инварианты и ограничения состояния")
        println()
        println(renderActive())
        demoRequests.forEach { request ->
            println()
            println("=== USER REQUEST ===")
            println(request)
            val result = agent.ask(request)
            println("=== INVARIANT CHECK ===")
            println(result.requestValidation.render())
            println("LLM called: ${result.llmCalled}")
            println("=== ASSISTANT RESPONSE ===")
            println(result.response)
        }
    }

    fun runCheckerDemo() {
        store.ensureSeed()
        val text = "Сделай серверную синхронизацию и пример на Java."
        val invariants = store.active()
        println("=== DETERMINISTIC CHECK ===")
        println(checker.check(text, "checker_demo", invariants).render())
        println()
        println("=== LLM CHECK ===")
        println(judge.judge(text, "checker_demo", invariants).render())
    }

    fun renderActive(): String = buildString {
        appendLine("=== ACTIVE INVARIANTS ===")
        store.active().forEach { appendLine("- ${it.id}: ${it.title} (${it.severity})") }
    }.trimEnd()
}
