import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
        val messages = session.readChatHistory(
            chatId = chatId,
            limit = request.limit,
            onlyLocal = request.onlyLocal,
            includeSender = request.includeSender,
        )

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

class TdlibAuthInspector(private val config: AppConfig) {
    fun inspect(resendCode: Boolean, requestQr: Boolean = false): String {
        val session = TdlibJsonSession(config)
        return session.inspectAuthorization(resendCode, requestQr)
    }

    fun inspectLive(resendCode: Boolean, requestQr: Boolean = false) {
        val session = TdlibJsonSession(config)
        session.inspectAuthorization(resendCode, requestQr, streamOutput = true)
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
        validateLoginConfig()
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
                        ?: throw IllegalStateException(
                            "Telegram is waiting for a login code.\n" +
                                describeAuthCodeInfo(state["code_info"]?.jsonObject) + "\n" +
                                "Re-run with TELEGRAM_CODE=... or use auth-qr if service messages do not arrive.",
                        )
                    send(
                        buildJsonObject {
                            put("@type", JsonPrimitive("checkAuthenticationCode"))
                            put("code", JsonPrimitive(code))
                        },
                    )
                }
                "authorizationStateWaitPassword" -> {
                    val password = config.telegramPassword
                        ?: throw IllegalStateException("Telegram account has 2FA enabled. Re-run with TELEGRAM_PASSWORD=... in the local environment.")
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

    fun inspectAuthorization(resendCode: Boolean, requestQr: Boolean, streamOutput: Boolean = false): String {
        validateLoginConfig()
        Files.createDirectories(config.tdlibSessionDir)
        Files.createDirectories(config.tdlibFilesDir)

        val output = StringBuilder()
        fun emit(line: String = "") {
            output.appendLine(line)
            if (streamOutput) println(line)
        }

        emit("TDLib auth diagnostics")
        emit("session: ${config.tdlibSessionDir.toAbsolutePath()}")
        emit("files: ${config.tdlibFilesDir.toAbsolutePath()}")
        emit("phone: ${maskPhone(config.telegramPhone)}")
        emit("resend requested: $resendCode")
        emit("qr requested: $requestQr")
        if (requestQr) emit("qr wait seconds: ${config.telegramQrWaitSeconds}")
        emit()

        var resendAttempted = false
        var qrAttempted = false
        var lastQrLink: String? = null
        val waitSeconds = if (requestQr) config.telegramQrWaitSeconds else config.timeoutSeconds
        val deadline = System.currentTimeMillis() + waitSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val update = receive(1.0) ?: continue
            if (update["@type"]?.jsonPrimitive?.contentOrNull == "error") {
                emit(tdlibError(update))
                return output.toString()
            }

            val state = update["authorization_state"]?.jsonObject ?: continue
            when (val stateType = state["@type"]?.jsonPrimitive?.contentOrNull) {
                "authorizationStateWaitTdlibParameters" -> {
                    emit("state: wait TDLib parameters -> sending local parameters")
                    send(tdlibParameters())
                }
                "authorizationStateWaitEncryptionKey" -> {
                    emit("state: wait encryption key -> using empty local database key")
                    send(
                        buildJsonObject {
                            put("@type", JsonPrimitive("checkDatabaseEncryptionKey"))
                            put("encryption_key", JsonPrimitive(""))
                        },
                    )
                }
                "authorizationStateWaitPhoneNumber" -> {
                    if (requestQr && !qrAttempted) {
                        emit("state: wait phone number -> requesting QR authentication")
                        qrAttempted = true
                        emit(requestResult("qr request", runCatching { request(qrAuthenticationRequest()) }))
                        emit()
                    } else {
                        emit("state: wait phone number -> sending configured TELEGRAM_PHONE")
                        send(
                            buildJsonObject {
                                put("@type", JsonPrimitive("setAuthenticationPhoneNumber"))
                                put("phone_number", JsonPrimitive(config.telegramPhone))
                            },
                        )
                    }
                }
                "authorizationStateWaitCode" -> {
                    emit("state: wait login code")
                    describeAuthCodeInfo(state["code_info"]?.jsonObject).lineSequence().forEach { emit(it) }
                    if (requestQr && !qrAttempted) {
                        emit("auth-qr requested from wait-code state -> trying requestQrCodeAuthentication")
                        qrAttempted = true
                        emit(requestResult("qr request", runCatching { request(qrAuthenticationRequest()) }))
                        emit()
                        continue
                    }
                    if (config.telegramCode != null) {
                        emit("TELEGRAM_CODE is configured -> sending checkAuthenticationCode")
                        send(
                            buildJsonObject {
                                put("@type", JsonPrimitive("checkAuthenticationCode"))
                                put("code", JsonPrimitive(config.telegramCode))
                            },
                        )
                        continue
                    }
                    if (resendCode && !resendAttempted) {
                        emit("auth-resend requested -> sending resendAuthenticationCode")
                        resendAttempted = true
                        emit(requestResult("resend", runCatching {
                            request(buildJsonObject { put("@type", JsonPrimitive("resendAuthenticationCode")) })
                        }))
                        emit()
                        continue
                    }
                    emit("Next step: use TELEGRAM_CODE=... or auth-qr.")
                    return output.toString()
                }
                "authorizationStateWaitOtherDeviceConfirmation" -> {
                    val link = state["link"]?.jsonPrimitive?.contentOrNull
                    if (link != lastQrLink) {
                        lastQrLink = link
                        emit("state: wait other device confirmation")
                        emit("Keep this command running while you scan. Stopping it expires the token.")
                        emit("Open or convert this tg:// link to a QR code, then confirm from an already logged-in Telegram device:")
                        emit(link ?: "link unavailable")
                        emit()
                        emit("Telegram mobile path: Settings -> Devices -> Link Desktop Device.")
                        emit("Waiting for confirmation...")
                    }
                    if (!requestQr) return output.toString()
                }
                "authorizationStateWaitPassword" -> {
                    emit("state: wait 2FA password")
                    emit("Next step: run with TELEGRAM_PASSWORD=... in local environment.")
                    return output.toString()
                }
                "authorizationStateReady" -> {
                    authorized = true
                    emit("state: ready")
                    emit("Telegram session is authorized; agent-demo mode can read chats now.")
                    return output.toString()
                }
                "authorizationStateClosed", "authorizationStateClosing" -> {
                    emit("state: $stateType")
                    return output.toString()
                }
            }
        }
        emit("Timed out while waiting for TDLib authorization state. Increase TELEGRAM_QR_WAIT_SECONDS or MCP_TIMEOUT_SECONDS if needed.")
        return output.toString()
    }

    fun readChatHistory(
        chatId: Long,
        limit: Int,
        onlyLocal: Boolean,
        includeSender: Boolean,
    ): List<TelegramMessage> {
        val messages = linkedMapOf<Long, TelegramMessage>()
        var fromMessageId = 0L
        var attempts = 0

        while (messages.size < limit && attempts < 10) {
            attempts += 1
            val batch = request(
                buildJsonObject {
                    put("@type", JsonPrimitive("getChatHistory"))
                    put("chat_id", JsonPrimitive(chatId))
                    put("from_message_id", JsonPrimitive(fromMessageId))
                    put("offset", JsonPrimitive(0))
                    put("limit", JsonPrimitive((limit - messages.size + 1).coerceIn(1, 100)))
                    put("only_local", JsonPrimitive(onlyLocal))
                },
            )["messages"]
                ?.jsonArray
                ?.mapNotNull { it.jsonObject.toTelegramMessage(includeSender) }
                ?: emptyList()

            if (batch.isEmpty()) break

            val sizeBefore = messages.size
            batch.forEach { messages.putIfAbsent(it.id, it) }

            val oldestId = batch.minOf { it.id }
            if (oldestId == fromMessageId || messages.size == sizeBefore) break
            fromMessageId = oldestId
        }

        return messages.values.take(limit)
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
        val extra = "day18-${++requestId}"
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

    private fun validateLoginConfig() {
        require(config.telegramApiId != null) { "TELEGRAM_API_ID is required for TELEGRAM_BACKEND=tdlib." }
        require(!config.telegramApiHash.isNullOrBlank()) { "TELEGRAM_API_HASH is required for TELEGRAM_BACKEND=tdlib." }
        require(!config.telegramPhone.isNullOrBlank()) { "TELEGRAM_PHONE is required for TELEGRAM_BACKEND=tdlib." }
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
        put("api_id", JsonPrimitive(config.telegramApiId!!))
        put("api_hash", JsonPrimitive(config.telegramApiHash!!))
        put("system_language_code", JsonPrimitive("en"))
        put("device_model", JsonPrimitive("ai-course-day-20-kotlin-cli"))
        put("system_version", JsonPrimitive(System.getProperty("os.name")))
        put("application_version", JsonPrimitive("1.0.0"))
        put("enable_storage_optimizer", JsonPrimitive(true))
    }

    private fun qrAuthenticationRequest(): JsonObject = buildJsonObject {
        put("@type", JsonPrimitive("requestQrCodeAuthentication"))
        put("other_user_ids", JsonArray(emptyList()))
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
        "messagePoll" -> "[poll]"
        else -> "[${type ?: "unknown message"}]"
    }
}

private fun tdlibError(error: JsonObject): String {
    val code = error["code"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "unknown TDLib error"
    return "TDLib error $code: $message"
}

private fun describeAuthCodeInfo(codeInfo: JsonObject?): String {
    codeInfo ?: return "code delivery: unknown"
    val current = deliveryLabel(codeInfo["type"]?.jsonObject)
    val next = deliveryLabel(codeInfo["next_type"]?.jsonObject)
    val timeout = codeInfo["timeout"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    return buildString {
        appendLine("current delivery: $current")
        appendLine("next delivery: $next")
        append("resend timeout seconds: $timeout")
    }
}

private fun deliveryLabel(delivery: JsonObject?): String {
    delivery ?: return "none"
    return when (delivery["@type"]?.jsonPrimitive?.contentOrNull) {
        "authenticationCodeTypeTelegramMessage" -> "Telegram service message"
        "authenticationCodeTypeSms" -> "SMS"
        "authenticationCodeTypeCall" -> "phone call"
        "authenticationCodeTypeFlashCall" -> "flash call"
        "authenticationCodeTypeMissedCall" -> "missed call"
        "authenticationCodeTypeFragment" -> "Fragment"
        else -> delivery["@type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
    }
}

private fun requestResult(label: String, result: Result<JsonObject>): String =
    result.fold(
        onSuccess = { "$label result: ${it["@type"]?.jsonPrimitive?.contentOrNull ?: it}" },
        onFailure = { "$label failed: ${it.message ?: it::class.simpleName}" },
    )

private fun maskPhone(phone: String?): String =
    phone?.replace(Regex("""\d(?=\d{2})"""), "*") ?: "(not configured)"
