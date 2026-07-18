package ru.ai.course.day35.releaseprep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
@Serializable
private data class ChatMessage(val role: String, val content: String)
@Serializable
private data class ResponseFormat(val type: String = "json_object")
@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("response_format") val responseFormat: ResponseFormat = ResponseFormat(),
)
@Serializable
private data class ProviderMessage(val content: String)
@Serializable
private data class ProviderChoice(val message: ProviderMessage)
@Serializable
private data class ProviderResponse(val choices: List<ProviderChoice>)
data class RawHttpResponse(val status: Int, val body: InputStream)
fun interface HttpExecutor { fun send(request: HttpRequest): RawHttpResponse }
class JavaHttpExecutor : HttpExecutor {
    internal val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    override fun send(request: HttpRequest): RawHttpResponse {
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        return RawHttpResponse(response.statusCode(), response.body())
    }
}
class ElizaClient(
    private val provider: ProviderConfig,
    private val executor: HttpExecutor = JavaHttpExecutor(),
    private val onCall: () -> Unit = {},
) {
    private var calls = 0
    val callCount: Int get() = calls
    fun complete(prompt: PromptBundle, credential: String): String {
        require(calls == 0) { "Only one Eliza call is allowed" }
        ContentPolicy.requireSafeSecret(credential)
        val body = AppJson.strict.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                provider.model,
                listOf(ChatMessage("system", prompt.system), ChatMessage("user", prompt.user)),
                Limits.OUTPUT_TOKENS,
            ),
        )
        val request = HttpRequest.newBuilder(provider.uri)
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "${provider.authScheme} $credential")
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        calls++
        onCall()
        val response = executor.send(request)
        response.body.use { stream ->
            val bytes = stream.readNBytes(Limits.HTTP_RESPONSE_BYTES + 1)
            require(bytes.size <= Limits.HTTP_RESPONSE_BYTES) { "Eliza response exceeds cap" }
            val text = decodeUtf8(bytes)
            require(response.status in 200..299) { "Eliza returned HTTP ${response.status}" }
            AppJson.rejectDuplicateKeys(text, "Eliza response envelope")
            val envelope = runCatching { AppJson.provider.decodeFromString(ProviderResponse.serializer(), text) }.getOrElse { error("Eliza response envelope is invalid") }
            require(envelope.choices.size == 1) { "Eliza must return exactly one choice" }
            return envelope.choices.single().message.content.also {
                ContentPolicy.validateText(it, "Eliza model JSON", Limits.HTTP_RESPONSE_BYTES)
                require(it.isNotBlank()) { "Eliza returned empty content" }
            }
        }
    }
}
