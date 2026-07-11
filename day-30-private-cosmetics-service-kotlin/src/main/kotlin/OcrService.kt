import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface OcrEngine {
    suspend fun diagnose(): Boolean
    suspend fun recognize(photo: UploadedPhoto): OcrResult
    fun currentProvider(): String = "local_tesseract"
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
        val deadline = System.nanoTime() + config.ocrTimeout.toNanos()
        val inputs = OcrImagePreprocessor.createInputs(photo, config.ocrLanguages)
        val texts = mutableListOf<String>()
        val inputNames = mutableListOf<String>()
        var firstFailure: OcrUnavailableException? = null
        for (input in inputs) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) break
            try {
                runTesseract(input, maxOf(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)))
                    .takeIf(String::isNotBlank)
                    ?.let { text ->
                        texts += text
                        inputNames += input.name
                    }
            } catch (error: OcrUnavailableException) {
                if (firstFailure == null) firstFailure = error
            }
        }
        OcrCandidateSelector.select(texts, inputNames)?.let { return@withContext it }
        firstFailure?.let { throw it }
        throw ApiProblem(
            status = io.ktor.http.HttpStatusCode.UnprocessableEntity,
            code = "ocr_empty",
            message = "На фотографии не удалось распознать состав.",
            hint = "Снимите блок Ingredients крупнее при ровном освещении или вставьте INCI вручную.",
        )
    }

    private fun runTesseract(input: TesseractInput, timeoutMillis: Long): String {
        val process = try {
            ProcessBuilder(
                config.ocrCommand,
                "stdin",
                "stdout",
                "-l",
                input.languages,
                "--oem",
                "1",
                "--psm",
                input.pageSegmentationMode.toString(),
                "--dpi",
                "300",
                "-c",
                "preserve_interword_spaces=1",
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
            process.outputStream.use { it.write(input.bytes) }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw OcrUnavailableException("Tesseract OCR did not finish before the configured timeout.")
            }
            val text = stdout.get(5, TimeUnit.SECONDS).trim().take(config.maxInciChars * 8)
            val errorText = stderr.get(5, TimeUnit.SECONDS).replace(Regex("\\s+"), " ").trim().take(300)
            if (process.exitValue() != 0) {
                throw OcrUnavailableException("Tesseract OCR failed${if (errorText.isBlank()) "." else ": $errorText"}")
            }
            return text
        } catch (error: OcrUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw OcrUnavailableException("Tesseract OCR process could not be completed.", error)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
