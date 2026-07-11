import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TesseractInput(
    val name: String,
    val bytes: ByteArray,
    val languages: String,
    val pageSegmentationMode: Int = 6,
)

object OcrImagePreprocessor {
    private const val MAX_WORKING_EDGE = 3_600
    private const val MAX_UPSCALE = 3.0

    fun createInputs(photo: UploadedPhoto, configuredLanguages: String): List<TesseractInput> {
        val raw = TesseractInput("raw", photo.bytes, configuredLanguages)
        val source = runCatching { ImageIO.read(ByteArrayInputStream(photo.bytes)) }.getOrNull() ?: return listOf(raw)
        val englishPreferred = configuredLanguages
            .split('+')
            .firstOrNull { it.equals("eng", ignoreCase = true) }
            ?: configuredLanguages
        return buildList {
            addEnhanced(source, "ingredients-band-inverted", Crop(0.095, 0.43, 0.91, 0.69), invert = true, englishPreferred)
            add(raw)
        }
    }

    private fun MutableList<TesseractInput>.addEnhanced(
        source: BufferedImage,
        name: String,
        crop: Crop,
        invert: Boolean,
        languages: String,
    ) {
        runCatching { enhance(source, crop, invert) }
            .getOrNull()
            ?.let { add(TesseractInput(name, it, languages)) }
    }

    private fun enhance(source: BufferedImage, crop: Crop, invert: Boolean): ByteArray {
        val left = (source.width * crop.left).roundToInt().coerceIn(0, source.width - 1)
        val top = (source.height * crop.top).roundToInt().coerceIn(0, source.height - 1)
        val right = (source.width * crop.right).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * crop.bottom).roundToInt().coerceIn(top + 1, source.height)
        val cropWidth = right - left
        val cropHeight = bottom - top
        val scale = min(MAX_UPSCALE, MAX_WORKING_EDGE.toDouble() / max(cropWidth, cropHeight))
        val width = max(1, (cropWidth * scale).roundToInt())
        val height = max(1, (cropHeight * scale).roundToInt())
        val originalPixels = IntArray(cropWidth * cropHeight)
        val histogram = IntArray(256)
        var index = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val argb = source.getRGB(x, y)
                val alpha = argb ushr 24 and 0xFF
                val rgb = if (alpha == 0xFF) argb else {
                    val red = ((argb ushr 16 and 0xFF) * alpha + 255 * (255 - alpha)) / 255
                    val green = ((argb ushr 8 and 0xFF) * alpha + 255 * (255 - alpha)) / 255
                    val blue = ((argb and 0xFF) * alpha + 255 * (255 - alpha)) / 255
                    red shl 16 or (green shl 8) or blue
                }
                val red = rgb ushr 16 and 0xFF
                val green = rgb ushr 8 and 0xFF
                val blue = rgb and 0xFF
                val gray = (red * 299 + green * 587 + blue * 114 + 500) / 1_000
                originalPixels[index++] = gray
                histogram[gray] += 1
            }
        }
        val cutoff = max(1, originalPixels.size / 100)
        val low = percentile(histogram, cutoff, ascending = true)
        val high = percentile(histogram, cutoff, ascending = false)
        val range = max(16, high - low)
        for (i in originalPixels.indices) {
            var value = ((originalPixels[i] - low) * 255.0 / range).roundToInt().coerceIn(0, 255)
            if (invert) value = 255 - value
            originalPixels[i] = value
        }
        val normalized = BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_BYTE_GRAY)
        normalized.raster.setSamples(0, 0, cropWidth, cropHeight, 0, originalPixels)
        val resized = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        resized.createGraphics().use { graphics ->
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(normalized, 0, 0, width, height, null)
        }
        val pixels = resized.raster.getSamples(0, 0, width, height, 0, null as IntArray?)
        for (i in pixels.indices) {
            pixels[i] = (((pixels[i] - 128) * 1.35) + 128).roundToInt().coerceIn(0, 255)
        }
        val sharpened = unsharp(pixels, width, height)
        val output = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        output.raster.setSamples(0, 0, width, height, 0, sharpened)
        return ByteArrayOutputStream().use { bytes ->
            check(ImageIO.write(output, "png", bytes)) { "PNG encoder is unavailable." }
            bytes.toByteArray()
        }
    }

    private fun percentile(histogram: IntArray, cutoff: Int, ascending: Boolean): Int {
        var seen = 0
        val indices = if (ascending) histogram.indices else histogram.indices.reversed()
        for (value in indices) {
            seen += histogram[value]
            if (seen >= cutoff) return value
        }
        return if (ascending) 0 else 255
    }

    private fun unsharp(source: IntArray, width: Int, height: Int): IntArray {
        if (width < 3 || height < 3) return source
        val result = source.copyOf()
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val center = row + x
                val blur = (
                    source[center - width - 1] + 2 * source[center - width] + source[center - width + 1] +
                        2 * source[center - 1] + 4 * source[center] + 2 * source[center + 1] +
                        source[center + width - 1] + 2 * source[center + width] + source[center + width + 1]
                    ) / 16
                val delta = source[center] - blur
                if (abs(delta) > 2) {
                    result[center] = (source[center] + delta * 1.6).roundToInt().coerceIn(0, 255)
                }
            }
        }
        return result
    }

    private data class Crop(val left: Double, val top: Double, val right: Double, val bottom: Double)
}

object OcrCandidateSelector {
    fun select(rawTexts: List<String>, inputNames: List<String> = emptyList()): OcrResult? {
        val scored = rawTexts.mapIndexedNotNull { order, raw ->
            val text = trimTrailingNoise(IngredientsSectionExtractor.extract(raw))
            if (text.isBlank()) return@mapIndexedNotNull null
            val marker = IngredientsSectionExtractor.containsMarker(raw)
            val separators = text.count { it == ',' || it == ';' || it == '\n' }
            val parts = text.split(Regex("[,;\\n]+")).count { part ->
                part.trim().length in 3..100 && part.any(Char::isLetter)
            }
            val letters = text.count(Char::isLetter)
            val replacementPenalty = text.count { it == '\uFFFD' || it.isISOControl() && it != '\n' }
            val garbagePenalty = text.count { character ->
                !character.isLetterOrDigit() && !character.isWhitespace() && character !in ",;:.()[]/%+-'&"
            }
            val linePenalty = max(0, text.lineSequence().count() - 20)
            val passBonus = when (inputNames.getOrNull(order)) {
                "ingredients-band-inverted" -> 4_000
                "raw" -> 250
                else -> 0
            }
            val score = (if (marker) 10_000 else 0) + passBonus + min(parts, 60) * 120 +
                min(separators, 60) * 40 + min(letters, 2_000) - replacementPenalty * 500 -
                garbagePenalty * 80 - linePenalty * 200 - max(0, text.length - 5_000) * 2
            ScoredText(order, text, marker, parts, score)
        }
        val best = scored.maxWithOrNull(compareBy<ScoredText> { it.score }.thenBy { -it.order }) ?: return null
        val quality = when {
            best.marker && best.parts >= 8 -> "high"
            best.marker && best.parts >= 4 || best.parts >= 8 -> "medium"
            else -> "low"
        }
        return OcrResult(text = best.text, quality = quality)
    }

    private fun trimTrailingNoise(text: String): String {
        val lines = text.lines().map { line ->
            val cleaned = line.replace(Regex("""^[^\p{L}\p{N}(]+"""), "")
                .replace(Regex("""(?<=[A-Za-z)])\.(?=\s+[A-Z])"""), ",")
                .trim()
            if (cleaned.endsWith('.') && cleaned.count { it == ',' } >= 2) cleaned.dropLast(1) + ',' else cleaned
        }.filter(String::isNotBlank).toMutableList()
        while (lines.size > 1 && lines.last().length > 70 && ',' !in lines.last() && ';' !in lines.last()) {
            lines.removeLast()
        }
        return lines.joinToString("\n").trim()
    }

    private data class ScoredText(
        val order: Int,
        val text: String,
        val marker: Boolean,
        val parts: Int,
        val score: Int,
    )
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
