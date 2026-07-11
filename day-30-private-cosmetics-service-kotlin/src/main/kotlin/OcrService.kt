import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface OcrEngine {
    suspend fun diagnose(): Boolean
    suspend fun recognize(photo: UploadedPhoto): OcrResult
}

class TesseractOcrEngine(private val config: AppConfig) : OcrEngine {
    override suspend fun diagnose(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = ProcessBuilder(config.ocrCommand, "--version").redirectErrorStream(true).start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
        }
    }

    override suspend fun recognize(photo: UploadedPhoto): OcrResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(
                config.ocrCommand,
                "stdin",
                "stdout",
                "-l",
                config.ocrLanguages,
                "--psm",
                "6",
            ).start()
        } catch (error: IOException) {
            throw OcrUnavailableException("Tesseract OCR is not installed or cannot be started.", error)
        }

        val stdout = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val stderr = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        try {
            process.outputStream.use { it.write(photo.bytes) }
            if (!process.waitFor(config.ocrTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw OcrUnavailableException("Tesseract OCR did not finish before the configured timeout.")
            }
            val text = stdout.get(5, TimeUnit.SECONDS).trim()
            val errorText = stderr.get(5, TimeUnit.SECONDS).replace(Regex("\\s+"), " ").trim().take(300)
            if (process.exitValue() != 0) {
                throw OcrUnavailableException("Tesseract OCR failed${if (errorText.isBlank()) "." else ": $errorText"}")
            }
            if (text.isBlank()) {
                throw ApiProblem(
                    status = io.ktor.http.HttpStatusCode.UnprocessableEntity,
                    code = "ocr_empty",
                    message = "На фотографии не удалось распознать состав.",
                    hint = "Сделайте снимок задней этикетки при ровном освещении или вставьте INCI вручную.",
                )
            }
            OcrResult(text = text, quality = estimateQuality(text))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun estimateQuality(text: String): String {
        val ingredientLikeParts = text.split(Regex("[,;\\n]+")).count { part ->
            part.trim().length in 3..80 && part.any(Char::isLetter)
        }
        return when {
            ingredientLikeParts >= 6 -> "high"
            ingredientLikeParts >= 3 -> "medium"
            else -> "low"
        }
    }
}
