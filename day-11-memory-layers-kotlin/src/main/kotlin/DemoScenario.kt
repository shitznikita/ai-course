object DemoScenario {
    val longTermSaves = listOf(
        Triple("long", "user_name", "Никита"),
        Triple("long", "preference", "краткие ответы с чек-листами"),
        Triple("long", "global_rule", "API-ключи хранить только в .env"),
    )

    val workingSaves = listOf(
        Triple("working", "task_name", "MVP приложения учета финансов"),
        Triple("working", "goal", "Собрать ТЗ для MVP"),
        Triple("working", "stage", "requirements"),
        Triple("working", "constraint", "первый релиз без backend"),
        Triple("working", "constraint", "срок MVP - 3 недели"),
        Triple("working", "decision", "добавить категории расходов"),
        Triple("working", "decision", "добавить экспорт CSV"),
        Triple("working", "open_question", "нужна ли авторизация?"),
    )

    val shortTermNotes = listOf(
        "Пользователь уточнил, что интерфейс должен быть очень простым.",
        "Ассистент предложил сначала зафиксировать экраны MVP.",
        "Пользователь попросил не добавлять push-уведомления в первый релиз.",
    )

    const val finalQuestion = "Сформируй краткое ТЗ для MVP."
}
