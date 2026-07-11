import kotlinx.serialization.decodeFromString
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class IngredientKnowledgeBase private constructor(
    val document: IngredientCardsDocument,
    val sourceDocument: SourcesDocument,
    val correctionDocument: OcrCorrectionsDocument,
    val productDocument: ProductsDocument,
) {
    private val sourceById = sourceDocument.sources.associateBy { it.id }
    private val aliasToCard: Map<String, IngredientCard> = buildMap {
        document.ingredients.forEach { card ->
            (card.aliases + card.inciName + card.id).forEach { alias ->
                val key = normalizeLookup(alias)
                val previous = put(key, card)
                if (previous != null && previous.id != card.id) {
                    throw KnowledgeException("Ingredient alias '$alias' is used by both '${previous.id}' and '${card.id}'.")
                }
            }
        }
    }
    private val canonicalToCard = document.ingredients.associateBy { normalizeLookup(it.inciName) }
    private val ocrCorrectionToCard: Map<String, IngredientCard> = buildMap {
        correctionDocument.corrections.forEach { correction ->
            val card = canonicalToCard[normalizeLookup(correction.canonicalInci)]
                ?: throw KnowledgeException("OCR correction target '${correction.canonicalInci}' has no ingredient card.")
            val key = normalizeLookup(correction.ocr)
            val previous = put(key, card)
            if (previous != null && previous.id != card.id) {
                throw KnowledgeException("OCR correction '${correction.ocr}' is ambiguous.")
            }
        }
    }
    private val productByLookup: Map<String, CatalogProduct> = buildMap {
        productDocument.products.forEach { product ->
            val names = product.aliases + product.name + "${product.brand} ${product.name}" + product.id
            names.forEach { name ->
                val key = normalizeLookup(name)
                val previous = put(key, product)
                if (previous != null && previous.id != product.id) {
                    throw KnowledgeException("Product alias '$name' is ambiguous.")
                }
            }
        }
    }

    val ingredientCount: Int get() = document.ingredients.size
    val productCount: Int get() = productDocument.products.size

    fun retrieve(inciText: String, maxCards: Int): EvidencePack {
        val parsed = InciParser.parse(inciText)
        val recognized = mutableListOf<RecognizedIngredient>()
        val unknown = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        parsed.ingredients.forEach { raw ->
            val match = lookupIngredient(raw)
            if (match == null) {
                unknown += raw
            } else if (seen.add(match.card.id)) {
                recognized += RecognizedIngredient(rawName = raw, card = match.card, ocrCorrected = match.ocrCorrected)
            }
        }
        val evidenceIngredients = recognized.withIndex()
            .sortedWith(compareByDescending<IndexedValue<RecognizedIngredient>> { evidencePriority(it.value.card) }.thenBy { it.index })
            .take(maxCards)
            .map { it.value }
        val sources = evidenceIngredients.flatMap { it.card.sourceIds }.distinct().map { sourceId ->
            sourceById[sourceId] ?: throw KnowledgeException("Unknown source '$sourceId' referenced by an ingredient card.")
        }
        val corrections = recognized.filter { it.ocrCorrected }.map {
            IngredientCorrection(rawName = it.rawName, canonicalInci = it.card.inciName)
        }
        return EvidencePack(parsed, recognized, evidenceIngredients, unknown.distinct().take(40), sources, corrections)
    }

    fun findProductExact(name: String): CatalogProduct? = productByLookup[normalizeLookup(name)]

    fun latestVersion(product: CatalogProduct): ProductVersion = product.versions.maxByOrNull { it.effectiveFrom }
        ?: throw KnowledgeException("Catalog product '${product.id}' has no versions.")

    fun versionAt(product: CatalogProduct, market: String?, asOf: String?): ProductVersion? = product.versions
        .filter { market.isNullOrBlank() || it.market.equals(market, ignoreCase = true) }
        .filter { asOf.isNullOrBlank() || it.effectiveFrom <= asOf }
        .maxByOrNull { it.effectiveFrom }

    fun evidenceSources(ids: Collection<String>): List<EvidenceSource> = ids.distinct().mapNotNull { sourceById[it] }.map {
        EvidenceSource(it.id, it.title, it.organization, it.url)
    }

    fun sources(ids: Collection<String>): List<KnowledgeSource> = ids.distinct().mapNotNull(sourceById::get)

    private fun lookupIngredient(raw: String): IngredientMatch? {
        val candidates = buildList {
            add(raw)
            add(raw.substringBefore('('))
            if ('(' in raw && ')' in raw) add(raw.substringAfter('(').substringBefore(')'))
            raw.split('/').forEach(::add)
        }
        return candidates.asSequence().map(::normalizeLookup).mapNotNull(aliasToCard::get).firstOrNull()
            ?.let { IngredientMatch(it, ocrCorrected = false) }
            ?: candidates.asSequence().map(::normalizeLookup).mapNotNull(ocrCorrectionToCard::get).firstOrNull()
                ?.let { IngredientMatch(it, ocrCorrected = true) }
    }

    private fun evidencePriority(card: IngredientCard): Int = when {
        card.id in setOf(
            "niacinamide", "tranexamic-acid", "arbutin", "retinol", "ascorbic-acid",
            "salicylic-acid", "glycolic-acid", "lactic-acid", "zinc-oxide", "titanium-dioxide",
        ) -> 100
        card.functions.any { it in setOf("uv_filter", "exfoliant", "keratolytic") } -> 90
        card.functions.any { it in setOf("humectant", "skin_conditioning", "emollient", "skin_protecting") } -> 60
        card.id == "aqua" -> 0
        else -> 30
    }

    private data class IngredientMatch(val card: IngredientCard, val ocrCorrected: Boolean)

    private fun validate() {
        requireUnique(document.ingredients.map { it.id }, "ingredient ID")
        requireUnique(sourceDocument.sources.map { it.id }, "source ID")
        requireUnique(correctionDocument.corrections.map { normalizeLookup(it.ocr) }, "OCR correction")
        requireUnique(productDocument.products.map { it.id }, "product ID")
        document.ingredients.forEach { card ->
            if (card.id.isBlank() || card.inciName.isBlank() || card.functions.isEmpty() || card.sourceIds.isEmpty()) {
                throw KnowledgeException("Ingredient card '${card.id}' is incomplete.")
            }
            card.sourceIds.forEach { id ->
                if (id !in sourceById) throw KnowledgeException("Ingredient '${card.id}' references missing source '$id'.")
            }
        }
        sourceDocument.sources.forEach { source ->
            val uri = runCatching { URI.create(source.url) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
                throw KnowledgeException("Source '${source.id}' must use a valid HTTPS URL.")
            }
        }
        productDocument.products.forEach { product ->
            if (!product.isFictional) throw KnowledgeException("The bundled demo catalog must contain fictional products only.")
            if (product.versions.isEmpty()) throw KnowledgeException("Product '${product.id}' has no formula version.")
        }
    }

    companion object {
        fun load(config: AppConfig): IngredientKnowledgeBase {
            val ingredients = read(config.knowledgeFile, IngredientCardsDocument.serializer())
            val sources = read(config.sourcesFile, SourcesDocument.serializer())
            val corrections = read(config.ocrCorrectionsFile, OcrCorrectionsDocument.serializer())
            val products = read(config.productCatalogFile, ProductsDocument.serializer())
            return IngredientKnowledgeBase(ingredients, sources, corrections, products).also { it.validate() }
        }

        private fun <T> read(path: Path, serializer: kotlinx.serialization.KSerializer<T>): T {
            if (!Files.isRegularFile(path)) throw KnowledgeException("Required local data file is missing: $path")
            return try {
                AppJson.strict.decodeFromString(serializer, Files.readString(path))
            } catch (error: Exception) {
                throw KnowledgeException("Cannot read local data file $path: ${error.message}", error)
            }
        }

        private fun requireUnique(values: List<String>, label: String) {
            if (values.any { it.isBlank() } || values.distinct().size != values.size) {
                throw KnowledgeException("Local data contains blank or duplicate $label values.")
            }
        }
    }
}

object InciParser {
    private val instructionPatterns = listOf(
        Regex("(?i)ignore\\s+(all\\s+)?(previous|prior|system).{0,30}(instructions?|prompt)"),
        Regex("(?i)(system|developer)\\s*prompt"),
        Regex("(?i)игнорируй.{0,40}(инструкц|промпт)"),
        Regex("(?i)выполни.{0,30}(команд|инструкц)"),
        Regex("(?i)ignore.{0,30}(safety|rules|guardrails)"),
        Regex("(?i)игнорируй.{0,30}(правила|безопасност|ограничен)"),
    )

    fun parse(raw: String): ParsedInci {
        val text = raw.trim()
        if (instructionPatterns.any { it.containsMatchIn(text) }) {
            throw ApiProblem(
                status = io.ktor.http.HttpStatusCode.UnprocessableEntity,
                code = "prompt_injection_detected",
                message = "Состав содержит текст, похожий на инструкцию, а не на INCI. Проверьте распознавание этикетки.",
            )
        }
        val cleaned = text
            .replace(Regex("(?i)^\\s*(ingredients?|ингредиенты|состав)\\s*:\\s*"), "")
            .replace(
                Regex("(?i)(Sodium\\s+Hyaluronate)[ \\t]*[\\n\\r]+[ \\t]*(Crossp(?:ol|al)ymer)"),
                "$1 $2",
            )
            .replace(Regex("(?<=\\d),(?=\\d-[A-Za-z])"), NUMERIC_COMMA_SENTINEL)
        val parts = cleaned.split(Regex("[,;\\n\\r]+"))
            .map { it.replace(NUMERIC_COMMA_SENTINEL, ",") }
            .map { it.trim().trim('.', '•', '-', '–') }
            .filter { it.isNotBlank() }
            .distinctBy(::normalizeLookup)
        return ParsedInci(rawText = text, ingredients = parts)
    }

    private const val NUMERIC_COMMA_SENTINEL = "<INCI_NUMERIC_COMMA>"
}

fun normalizeLookup(value: String): String = value
    .uppercase(Locale.ROOT)
    .replace('Ё', 'Е')
    .replace(Regex("[^A-ZА-Я0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

object ProductTypeResolver {
    val supportedHints: Set<String> = setOf(
        "face_cleanser",
        "face_toner",
        "face_serum",
        "face_moisturizer",
        "face_sunscreen",
        "other",
    )

    fun resolve(value: String?): String? {
        val text = value?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (text.isBlank()) return null
        return when {
            listOf("spf", "sunscreen", "sun screen", "sunblock", "солнцезащ", "санскрин").any(text::contains) ->
                "face_sunscreen"
            listOf("cleanser", "face wash", "cleansing gel", "умыван", "очищающ", "пенка", "гель для лица").any(text::contains) ->
                "face_cleanser"
            listOf("toner", "tonic", "тонер", "тоник", "тонер-пэд", "тонер пэд", "тонерн", "пэды").any(text::contains) ->
                "face_toner"
            listOf("serum", "ampoule", "сывор", "ампул").any(text::contains) ->
                "face_serum"
            listOf("moisturizer", "moisturiser", "face cream", "facial cream", "крем", "эмульсия").any(text::contains) ->
                "face_moisturizer"
            else -> null
        }
    }
}
