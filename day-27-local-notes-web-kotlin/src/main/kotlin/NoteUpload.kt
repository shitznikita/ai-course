import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

suspend fun ApplicationCall.readUploadedNote(config: AppConfig): UploadedNote {
    if (!request.contentType().match(ContentType.MultiPart.FormData)) {
        throw ApiProblem(
            status = HttpStatusCode.BadRequest,
            code = "multipart_required",
            message = "Отправьте заметку как multipart/form-data в поле 'note'.",
        )
    }

    var uploadedNote: UploadedNote? = null
    var fileCount = 0
    val multipart = receiveMultipart(formFieldLimit = 1_024L * 1_024L)
    multipart.forEachPart { part ->
        try {
            when (part) {
                is PartData.FileItem -> {
                    fileCount += 1
                    if (part.name != "note" || fileCount > 1) {
                        throw ApiProblem(
                            status = HttpStatusCode.BadRequest,
                            code = "single_note_required",
                            message = "Нужно загрузить ровно один файл в поле 'note'.",
                        )
                    }
                    val fileName = safeFileName(part.originalFileName)
                    val format = supportedFormat(fileName)
                    val bytes = try {
                        part.provider()
                            .readRemaining(config.maxUploadBytes.toLong() + 1)
                            .readByteArray()
                    } catch (error: Exception) {
                        throw ApiProblem(
                            status = HttpStatusCode.BadRequest,
                            code = "upload_read_failed",
                            message = "Не удалось прочитать загруженную заметку.",
                        )
                    }
                    if (bytes.size > config.maxUploadBytes) {
                        throw ApiProblem(
                            status = HttpStatusCode.PayloadTooLarge,
                            code = "file_too_large",
                            message = "Заметка больше допустимого лимита ${config.maxUploadBytes} байт.",
                        )
                    }
                    val text = decodeUtf8(bytes)
                    if (text.isBlank()) {
                        throw ApiProblem(
                            status = HttpStatusCode.UnprocessableEntity,
                            code = "empty_note",
                            message = "Заметка пуста после UTF-8 декодирования.",
                        )
                    }
                    if (text.length > config.maxNoteChars) {
                        throw ApiProblem(
                            status = HttpStatusCode.UnprocessableEntity,
                            code = "note_too_long",
                            message = "Заметка больше допустимого лимита ${config.maxNoteChars} символов.",
                        )
                    }
                    uploadedNote = UploadedNote(fileName = fileName, format = format, text = text)
                }

                else -> throw ApiProblem(
                    status = HttpStatusCode.BadRequest,
                    code = "single_note_required",
                    message = "Нужно загрузить ровно один файл в поле 'note'.",
                )
            }
        } finally {
            part.dispose()
        }
    }

    return uploadedNote ?: throw ApiProblem(
        status = HttpStatusCode.BadRequest,
        code = "note_missing",
        message = "Файл заметки в поле 'note' не найден.",
    )
}

private fun safeFileName(originalFileName: String?): String {
    val name = originalFileName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        .orEmpty()
    if (name.isBlank() || name.length > 255) {
        throw ApiProblem(
            status = HttpStatusCode.UnprocessableEntity,
            code = "invalid_filename",
            message = "Имя файла заметки некорректно.",
        )
    }
    return name
}

private fun supportedFormat(fileName: String): String {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (extension !in setOf("txt", "md")) {
        throw ApiProblem(
            status = HttpStatusCode.UnsupportedMediaType,
            code = "unsupported_format",
            message = "Поддерживаются только заметки .txt и .md.",
        )
    }
    return extension
}

private fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: CharacterCodingException) {
    throw ApiProblem(
        status = HttpStatusCode.UnprocessableEntity,
        code = "utf8_required",
        message = "Заметка должна быть в кодировке UTF-8.",
    )
}
