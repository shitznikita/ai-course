import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.isDirectory

class TdlibTelegramMessageReader(private val config: AppConfig) : TelegramMessageReader {
    private val session by lazy { TdlibJsonSession(config) }

    override fun readMessages(request: TelegramReadRequest): TelegramReadResult {
        session.ensureAuthorized()
        val chatId = session.resolveChatId(request.chat)
        val response = session.request(
            buildJsonObject {
                put("@type", JsonPrimitive("getChatHistory"))
                put("chat_id", JsonPrimitive(chatId))
                put("from_message_id", JsonPrimitive(0))
                put("offset", JsonPrimitive(0))
                put("limit", JsonPrimitive(request.limit))
                put("only_local", JsonPrimitive(request.onlyLocal))
            },
        )

        val messages = response["messages"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toTelegramMessage(request.includeSender) }
            ?: emptyList()

        return TelegramReadResult(
            backend = "tdlib",
            chat = request.chat,
            requestedLimit = request.limit,
            onlyLocal = request.onlyLocal,
            includeSender = request.includeSender,
            messages = messages,
        )
    }
}

private class TdlibJsonSession(private val config: AppConfig) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val native: TdJsonNative = loadNative(config.tdlibLibraryPath)
    private val client: Pointer = native.td_json_client_create()
    private var authorized = false
    private var requestId = 0L

    init {
        native.td_json_client_execute(
            null,
            json.encodeToString(
                buildJsonObject {
                    put("@type", JsonPrimitive("setLogVerbosityLevel"))
                    put("new_verbosity_level", JsonPrimitive(1))
                },
            ),
        )
    }

    fun ensureAuthorized() {
        if (authorized) return
        require(config.telegramApiId != null) { "TELEGRAM_API_ID is required for TELEGRAM_BACKEND=tdlib." }
        require(!config.telegramApiHash.isNullOrBlank()) { "TELEGRAM_API_HASH is required for TELEGRAM_BACKEND=tdlib." }
        require(!config.telegramPhone.isNullOrBlank()) { "TELEGRAM_PHONE is required for TELEGRAM_BACKEND=tdlib." }
        Files.createDirectories(config.tdlibSessionDir)
        Files.createDirectories(config.tdlibFilesDir)

        val deadline = System.currentTimeMillis() + config.timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val update = receive(1.0) ?: continue
            if (update["@type"]?.jsonPrimitive?.contentOrNull == "error") {
                throw IllegalStateException(tdlibError(update))
            }
            val state = update["authorization_state"]?.jsonObject ?: continue
            when (state["@type"]?.jsonPrimitive?.contentOrNull) {
                "authorizationStateWaitTdlibParameters" -> send(tdlibParameters())
                "authorizationStateWaitEncryptionKey" -> send(
                    buildJsonObject {
                        put("@type", JsonPrimitive("checkDatabaseEncryptionKey"))
                        put("encryption_key", JsonPrimitive(""))
                    },
                )
                "authorizationStateWaitPhoneNumber" -> send(
                    buildJsonObject {
                        put("@type", JsonPrimitive("setAuthenticationPhoneNumber"))
                        put("phone_number", JsonPrimitive(config.telegramPhone))
                    },
                )
                "authorizationStateWaitCode" -> {
                    val code = config.telegramCode
                        ?: throw IllegalStateException("Telegram sent a login code. Re-run with TELEGRAM_CODE=... in the environment; do not commit it.")
                    send(
                        buildJsonObject {
                            put("@type", JsonPrimitive("checkAuthenticationCode"))
                            put("code", JsonPrimitive(code))
                        },
                    )
                }
                "authorizationStateWaitPassword" -> {
                    val password = config.telegramPassword
                        ?: throw IllegalStateException("Telegram account has 2FA enabled. Re-run with TELEGRAM_PASSWORD=... in the environment; do not commit it.")
                    send(
                        buildJsonObject {
                            put("@type", JsonPrimitive("checkAuthenticationPassword"))
                            put("password", JsonPrimitive(password))
                        },
                    )
                }
                "authorizationStateReady" -> {
                    authorized = true
                    return
                }
                "authorizationStateClosed", "authorizationStateClosing" -> error("TDLib authorization closed.")
            }
        }
        error("Timed out while waiting for TDLib authorization. Increase MCP_TIMEOUT_SECONDS if needed.")
    }

    fun resolveChatId(chat: String): Long {
        chat.toLongOrNull()?.let { return it }
        val username = chat.removePrefix("@")
        val response = request(
            buildJsonObject {
                put("@type", JsonPrimitive("searchPublicChat"))
                put("username", JsonPrimitive(username))
            },
        )
        return response["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: error("Could not resolve Telegram chat '$chat'. Use numeric chat id or public username.")
    }

    fun request(body: JsonObject): JsonObject {
        val extra = "day17-${++requestId}"
        val requestBody = JsonObject(body + ("@extra" to JsonPrimitive(extra)))
        send(requestBody)

        val deadline = System.currentTimeMillis() + config.timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val response = receive(1.0) ?: continue
            if (response["@extra"]?.jsonPrimitive?.contentOrNull != extra) continue
            if (response["@type"]?.jsonPrimitive?.contentOrNull == "error") {
                throw IllegalStateException(tdlibError(response))
            }
            return response
        }
        error("Timed out waiting for TDLib response to ${body["@type"]?.jsonPrimitive?.contentOrNull}.")
    }

    private fun send(body: JsonObject) {
        native.td_json_client_send(client, json.encodeToString(body))
    }

    private fun receive(timeoutSeconds: Double): JsonObject? {
        val raw = native.td_json_client_receive(client, timeoutSeconds) ?: return null
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun tdlibParameters(): JsonObject = buildJsonObject {
        put("@type", JsonPrimitive("setTdlibParameters"))
        put("use_test_dc", JsonPrimitive(false))
        put("database_directory", JsonPrimitive(config.tdlibSessionDir.toAbsolutePath().toString()))
        put("files_directory", JsonPrimitive(config.tdlibFilesDir.toAbsolutePath().toString()))
        put("use_file_database", JsonPrimitive(true))
        put("use_chat_info_database", JsonPrimitive(true))
        put("use_message_database", JsonPrimitive(true))
        put("use_secret_chats", JsonPrimitive(false))
        put("api_id", JsonPrimitive(config.telegramApiId))
        put("api_hash", JsonPrimitive(config.telegramApiHash))
        put("system_language_code", JsonPrimitive("en"))
        put("device_model", JsonPrimitive("ai-course-kotlin-cli"))
        put("system_version", JsonPrimitive(System.getProperty("os.name")))
        put("application_version", JsonPrimitive("1.0.0"))
        put("enable_storage_optimizer", JsonPrimitive(true))
    }
}

private interface TdJsonNative : Library {
    fun td_json_client_create(): Pointer
    fun td_json_client_send(client: Pointer, request: String)
    fun td_json_client_receive(client: Pointer, timeout: Double): String?
    fun td_json_client_execute(client: Pointer?, request: String): String?
}

private fun loadNative(libraryPath: String?): TdJsonNative {
    require(!libraryPath.isNullOrBlank()) {
        "TDLIB_LIBRARY_PATH is required for TELEGRAM_BACKEND=tdlib. Point it to libtdjson.dylib/.so/.dll or a directory containing tdjson."
    }
    val path = Path(libraryPath)
    return if (path.isDirectory()) {
        NativeLibrary.addSearchPath("tdjson", path.toAbsolutePath().toString())
        Native.load("tdjson", TdJsonNative::class.java)
    } else {
        Native.load(path.toAbsolutePath().toString(), TdJsonNative::class.java)
    }
}

private fun JsonObject.toTelegramMessage(includeSender: Boolean): TelegramMessage? {
    val id = this["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
    val date = this["date"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
    val sender = if (includeSender) senderLabel(this["sender_id"]?.jsonObject) else null
    val content = this["content"]?.jsonObject
    val text = extractMessageText(content)
    return TelegramMessage(
        id = id,
        dateIso = unixSecondsToIso(date),
        sender = sender,
        text = text,
    )
}

private fun senderLabel(sender: JsonObject?): String? {
    sender ?: return null
    val type = sender["@type"]?.jsonPrimitive?.contentOrNull ?: return "unknown"
    return when (type) {
        "messageSenderUser" -> "user:${sender["user_id"]?.jsonPrimitive?.contentOrNull ?: "unknown"}"
        "messageSenderChat" -> "chat:${sender["chat_id"]?.jsonPrimitive?.contentOrNull ?: "unknown"}"
        else -> type
    }
}

private fun extractMessageText(content: JsonObject?): String {
    content ?: return "[empty content]"
    return when (val type = content["@type"]?.jsonPrimitive?.contentOrNull) {
        "messageText" -> content["text"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
        "messagePhoto" -> content["caption"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.ifBlank { "[photo]" } ?: "[photo]"
        "messageVideo" -> content["caption"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.ifBlank { "[video]" } ?: "[video]"
        "messageDocument" -> content["caption"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.ifBlank { "[document]" } ?: "[document]"
        else -> "[$type]"
    }
}

private fun tdlibError(error: JsonObject): String {
    val code = error["code"]?.jsonPrimitive?.intOrNull
    val message = error["message"]?.jsonPrimitive?.contentOrNull
    val fatal = error["@fatal"]?.jsonPrimitive?.booleanOrNull
    return "TDLib error code=$code fatal=$fatal message=${message ?: "no message"}"
}
