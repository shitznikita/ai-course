import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

suspend fun ApplicationCall.readUploadedPhoto(config: AppConfig): UploadedPhoto {
    configureMemoryOnlyImageIo()
    if (!request.contentType().match(ContentType.MultiPart.FormData)) {
        throw ApiProblem(HttpStatusCode.BadRequest, "multipart_required", "Отправьте JPEG или PNG как multipart/form-data в поле 'photo'.")
    }
    var result: UploadedPhoto? = null
    var fileCount = 0
    val multipart = receiveMultipart(formFieldLimit = config.maxPhotoBytes.toLong() + 8_192)
    multipart.forEachPart { part ->
        try {
            if (part !is PartData.FileItem || part.name != "photo") {
                throw ApiProblem(HttpStatusCode.BadRequest, "single_photo_required", "Нужно загрузить ровно один файл в поле 'photo'.")
            }
            fileCount += 1
            if (fileCount > 1) {
                throw ApiProblem(HttpStatusCode.BadRequest, "single_photo_required", "Нужно загрузить ровно один файл в поле 'photo'.")
            }
            val fileName = safePhotoFileName(part.originalFileName)
            val bytes = part.provider().readRemaining(config.maxPhotoBytes.toLong() + 1).readByteArray()
            if (bytes.size > config.maxPhotoBytes) {
                throw ApiProblem(HttpStatusCode.PayloadTooLarge, "photo_too_large", "Фотография превышает лимит ${config.maxPhotoBytes} байт.")
            }
            val format = detectImageFormat(bytes)
            val (width, height) = readDimensions(bytes)
            val pixels = width.toLong() * height.toLong()
            if (pixels <= 0 || pixels > config.maxImagePixels) {
                throw ApiProblem(
                    HttpStatusCode.PayloadTooLarge,
                    "image_dimensions_too_large",
                    "Изображение содержит $pixels пикселей; допустимо не более ${config.maxImagePixels}.",
                )
            }
            result = UploadedPhoto(fileName, format, width, height, bytes)
        } finally {
            part.dispose()
        }
    }
    return result ?: throw ApiProblem(HttpStatusCode.BadRequest, "photo_missing", "Файл в поле 'photo' не найден.")
}

fun configureMemoryOnlyImageIo() {
    ImageIO.setUseCache(false)
}

private fun safePhotoFileName(original: String?): String {
    val name = original?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
    if (name.isBlank() || name.length > 255) {
        throw ApiProblem(HttpStatusCode.UnprocessableEntity, "invalid_filename", "Имя файла фотографии некорректно.")
    }
    return name
}

private fun detectImageFormat(bytes: ByteArray): String {
    val png = bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    )
    val jpeg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    return when {
        png -> "png"
        jpeg -> "jpeg"
        else -> throw ApiProblem(HttpStatusCode.UnsupportedMediaType, "unsupported_image", "Поддерживаются только настоящие JPEG и PNG.")
    }
}

private fun readDimensions(bytes: ByteArray): Pair<Int, Int> {
    val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        ?: throw ApiProblem(HttpStatusCode.UnprocessableEntity, "invalid_image", "Не удалось прочитать изображение.")
    input.use {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) throw ApiProblem(HttpStatusCode.UnprocessableEntity, "invalid_image", "Не удалось прочитать изображение.")
        val reader = readers.next()
        return try {
            reader.input = input
            reader.getWidth(0) to reader.getHeight(0)
        } catch (_: Exception) {
            throw ApiProblem(HttpStatusCode.UnprocessableEntity, "invalid_image", "Загруженный файл повреждён.")
        } finally {
            reader.dispose()
        }
    }
}
