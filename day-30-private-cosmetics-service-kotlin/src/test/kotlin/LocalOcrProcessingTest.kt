import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalOcrProcessingTest {
    @Test
    fun `preprocessor creates bounded deterministic variants and preserves raw input`() {
        val image = BufferedImage(1_600, 1_000, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics ->
            try {
                graphics.color = Color(205, 145, 45)
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.color = Color.WHITE
                graphics.drawString("Ingredients Water, Glycerin, Niacinamide", 180, 500)
            } finally {
                graphics.dispose()
            }
        }
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, "jpeg", output))
            output.toByteArray()
        }
        val photo = UploadedPhoto("label.jpg", "jpeg", image.width, image.height, bytes)

        val first = OcrImagePreprocessor.createInputs(photo, "eng+rus")
        val second = OcrImagePreprocessor.createInputs(photo, "eng+rus")

        assertEquals(listOf("ingredients-band-inverted", "raw"), first.map { it.name })
        assertContentEquals(bytes, first.last().bytes)
        assertEquals("eng", first.single { it.name == "ingredients-band-inverted" }.languages)
        first.filter { it.name != "raw" }.forEach { input ->
            val processed = assertNotNull(ImageIO.read(ByteArrayInputStream(input.bytes)))
            assertTrue(maxOf(processed.width, processed.height) <= 3_600)
        }
        assertEquals(first.map { it.bytes.contentHashCode() }, second.map { it.bytes.contentHashCode() })
    }

    @Test
    fun `candidate selector prefers explicit ingredients section and strips directions`() {
        val noisyMarketing = "NIACINAMIDE\nBRIGHTENING TONER PAD\nTARGETS PORES\nEVENLY CLEAR SKIN"
        val label = """
            PRODUCT CLAIM
            Ingredients: Water, Glycerin, Niacinamide, Panthenol, Allantoin,
            Caffeine, Tocopherol, Citric Acid
            Directions: wipe over clean skin
        """.trimIndent()

        val result = assertNotNull(OcrCandidateSelector.select(listOf(noisyMarketing, label)))

        assertEquals(
            "Water, Glycerin, Niacinamide, Panthenol, Allantoin,\nCaffeine, Tocopherol, Citric Acid",
            result.text,
        )
        assertEquals("high", result.quality)
        assertEquals("local_tesseract", result.provider)
    }

    @Test
    fun `candidate selector marks short markerless output as low quality`() {
        val result = assertNotNull(OcrCandidateSelector.select(listOf("NIACINAMIDE\nTONER PAD\nCLEAR SKIN")))

        assertEquals("low", result.quality)
    }

    @Test
    fun `candidate selector removes obvious trailing OCR noise without inventing ingredients`() {
        val raw = """
            OCR f dients Water, Glycerin, Niacinamide.
            _ Panthenol, Allantoin, Caffeine
            THIS IS A VERY LONG OCR ARTIFACT WITHOUT ANY LIST SEPARATORS OR USEFUL INGREDIENT STRUCTURE XXXXXXXXXXXXX
        """.trimIndent()

        val result = assertNotNull(OcrCandidateSelector.select(listOf(raw)))

        assertEquals("Water, Glycerin, Niacinamide,\nPanthenol, Allantoin, Caffeine", result.text)
    }

    @Test
    fun `candidate selector removes long short-token artifact with one accidental comma`() {
        val artifact = "OE EST Eee Rae ee Te OEE By 0 Nil Fae el, SU Te Se reer ey Tein ae SOREL Se ase ay See ee Cee Oe oe er Sa"
        val raw = "Water, Glycerin, Niacinamide\nCrosspolymer, Hydrolyzed Sodium Hyaluronate, Potassium Hyaluronate\n$artifact"

        val result = assertNotNull(OcrCandidateSelector.select(listOf(raw)))

        assertEquals(
            "Water, Glycerin, Niacinamide\nCrosspolymer, Hydrolyzed Sodium Hyaluronate, Potassium Hyaluronate",
            result.text,
        )
    }
}
