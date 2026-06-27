import java.time.Instant

class CoursePromptService(
    private val config: AppConfig,
    private val reader: TelegramMessageReader = TelegramBackends.create(config),
    private val promptGenerator: CoursePromptGenerator = createPromptGenerator(config),
    private val storage: CourseRunStorage = CourseRunStorage(config),
) {
    fun generate(request: CourseDayPromptRequest): CourseDayPromptResult {
        val telegram = reader.readMessages(
            TelegramReadRequest(
                chat = request.chat,
                limit = request.limit,
                onlyLocal = false,
                includeSender = false,
            ),
        )
        val window = CourseDayWindowExtractor.extract(
            messages = telegram.messages,
            requestedCourseDay = request.courseDay,
        )
        val generated = promptGenerator.generate(window)
        val result = CourseDayPromptResult(
            generatedAtIso = Instant.now().toString(),
            requestedCourseDay = request.courseDay,
            detectedDay = window.detectedDay,
            telegram = telegram,
            selectedMessages = window.selectedMessages,
            markers = window.markers,
            ignoredBefore = window.ignoredBefore,
            ignoredAfter = window.ignoredAfter,
            nextMarkerDay = window.nextMarker?.day,
            llmMode = generated.mode,
            highlights = generated.highlights,
            prompt = generated.prompt,
            storage = null,
        )
        return if (request.persist) storage.save(result) else result
    }

    fun latest(): LatestPromptResult = storage.readLatest()
}
