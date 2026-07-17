package ru.ai.course.day33.supportassistant

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

fun interface SupportResponseGenerator {
    fun generate(prompt: PromptPack): LlmReply
}

class ElizaLlmClient(
    private val config: AppConfig,
    private val transport: HttpTransport = JavaHttpTransport(config.llmConnectTimeout),
) : SupportResponseGenerator {
    private val apiKey = requireNotNull(config.llmApiKey) { "LLM_API_KEY is required for live ask/chat." }

    init {
        require(apiKey.isNotBlank()) { "LLM_API_KEY is required for live ask/chat." }
        require(config.llmAuthScheme.matches(Regex("""[A-Za-z][A-Za-z0-9_-]{0,30}"""))) {
            "LLM_AUTH_SCHEME is invalid."
        }
        require(config.llmEndpoint() == AppConfig.REVIEWED_LLM_ENDPOINT) {
            "Authorization is allowed only for the reviewed Eliza endpoint."
        }
        require(config.fixturePath == committedFixturePath(config)) {
            "Live cloud mode is allowed only with the committed synthetic Day 33 fixture."
        }
    }

    override fun generate(prompt: PromptPack): LlmReply {
        try {
            requireSyntheticPrompt(prompt)
        } catch (error: IllegalArgumentException) {
            throw LlmPreflightException("Cloud safety preflight blocked the request.", error)
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.llmModel))
            put("messages", buildJsonArray {
                add(message("system", prompt.system))
                add(message("user", prompt.user))
            })
            put("response_format", buildJsonObject { put("type", JsonPrimitive("json_object")) })
            put("temperature", JsonPrimitive(0))
            put("max_tokens", JsonPrimitive(config.llmMaxOutputTokens))
        }
        val result = transport.execute(
            HttpCall(
                method = "POST",
                uri = config.llmEndpoint(),
                headers = mapOf(
                    "Authorization" to "${config.llmAuthScheme} $apiKey",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                    "User-Agent" to "ai-course-day-33-support-assistant",
                ),
                body = SupportJson.compact.encodeToString(body),
                timeout = config.llmRequestTimeout,
                maxResponseBytes = config.llmMaxResponseBytes,
            ),
        )
        if (result.status !in 200..299) {
            throw LlmHttpException("LLM endpoint returned HTTP ${result.status}.")
        }
        val contentType = result.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value?.firstOrNull()?.lowercase()
        require(contentType == null || "json" in contentType) {
            "LLM endpoint returned a non-JSON content type."
        }
        val envelope = runCatching { SupportJson.tolerant.parseToJsonElement(result.body).jsonObject }
            .getOrElse { throw LlmProtocolException("LLM endpoint returned malformed JSON.") }
        val json = envelope["response"] as? JsonObject ?: envelope
        val choices = json["choices"] as? JsonArray
            ?: throw LlmProtocolException("LLM response has no choices.")
        require(choices.size == 1) { "LLM response must contain exactly one choice." }
        val message = (choices.single() as? JsonObject)?.get("message") as? JsonObject
            ?: throw LlmProtocolException("LLM response choice has no message.")
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw LlmProtocolException("LLM response has empty content.")
        val usage = json["usage"] as? JsonObject
        return LlmReply(
            model = (json["model"] as? JsonPrimitive)?.contentOrNull ?: config.llmModel,
            content = content,
            promptTokens = usage?.get("prompt_tokens")
                ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() },
            completionTokens = usage?.get("completion_tokens")
                ?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() },
        )
    }

    private fun requireSyntheticPrompt(prompt: PromptPack) {
        SupportDataPolicy.requireCloudSafe(
            fixturePath = config.fixturePath,
            knowledgeDirectory = config.knowledgeDirectory,
            maxChunkChars = config.limits.maxChunkChars,
            prompt = prompt,
        )
    }

    private fun committedFixturePath(config: AppConfig) =
        config.repositoryRoot.resolve("day-33-support-assistant-kotlin/fixtures/support-data.json").normalize()

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", JsonPrimitive(content))
    }
}

class LlmHttpException(message: String) : IllegalStateException(message)
class LlmProtocolException(message: String) : IllegalStateException(message)
class LlmPreflightException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)
