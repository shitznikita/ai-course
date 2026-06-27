import java.time.Instant

class CoursePipelineService(
    private val config: AppConfig,
    private val reader: TelegramMessageReader = TelegramBackends.create(config),
    private val summarizer: DiscussionSummarizer = createDiscussionSummarizer(config),
    private val storage: PipelineStorage = PipelineStorage(config),
) {
    fun search(request: SearchCourseDayMessagesRequest): SearchCourseDayMessagesResult {
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
        return SearchCourseDayMessagesResult(
            generatedAtIso = Instant.now().toString(),
            requestedCourseDay = request.courseDay,
            detectedDay = window.detectedDay,
            telegram = telegram,
            selectedMessages = window.selectedMessages,
            markers = window.markers,
            ignoredBefore = window.ignoredBefore,
            ignoredAfter = window.ignoredAfter,
            nextMarkerDay = window.nextMarker?.day,
        )
    }

    fun summarize(handoffJson: String): CourseDiscussionSummaryResult {
        val searchResult = SearchCourseDayMessagesResult.fromHandoffJson(handoffJson)
        return summarizer.summarize(searchResult)
    }

    fun save(handoffJson: String): SavePipelineResult {
        val summary = CourseDiscussionSummaryResult.fromHandoffJson(handoffJson)
        return storage.save(summary)
    }
}
