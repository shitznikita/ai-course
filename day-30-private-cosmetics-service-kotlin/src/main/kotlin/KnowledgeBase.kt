import kotlinx.serialization.decodeFromString
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class IngredientKnowledgeBase private constructor(
    val document: IngredientCardsDocument,
    val sourceDocument: SourcesDocument,
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
            val card = lookupIngredient(raw)
            if (card == null) {
                unknown += raw
            } else if (seen.add(card.id) && recognized.size < maxCards) {
                recognized += RecognizedIngredient(rawName = raw, card = card)
            }
        }
        val sources = recognized.flatMap { it.card.sourceIds }.distinct().map { sourceId ->
            sourceById[sourceId] ?: throw KnowledgeException("Unknown source '$sourceId' referenced by an ingredient card.")
        }
        return EvidencePack(parsed, recognized, unknown.distinct().take(40), sources)
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

    private fun lookupIngredient(raw: String): IngredientCard? {
        val candidates = buildList {
            add(raw)
            add(raw.substringBefore('('))
            if ('(' in raw && ')' in raw) add(raw.substringAfter('(').substringBefore(')'))
            raw.split('/').forEach(::add)
        }
        return candidates.asSequence().map(::normalizeLookup).mapNotNull(aliasToCard::get).firstOrNull()
    }

    private fun validate() {
        requireUnique(document.ingredients.map { it.id }, "ingredient ID")
        requireUnique(sourceDocument.sources.map { it.id }, "source ID")
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
            val products = read(config.productCatalogFile, ProductsDocument.serializer())
            return IngredientKnowledgeBase(ingredients, sources, products).also { it.validate() }
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
        val cleaned = text.replace(Regex("(?i)^\\s*(ingredients?|ингредиенты|состав)\\s*:\\s*"), "")
        val parts = cleaned.split(Regex("[,;\\n\\r]+"))
            .map { it.trim().trim('.', '•', '-', '–') }
            .filter { it.isNotBlank() }
            .distinctBy(::normalizeLookup)
        return ParsedInci(rawText = text, ingredients = parts)
    }
}

fun normalizeLookup(value: String): String = value
    .uppercase(Locale.ROOT)
    .replace('Ё', 'Е')
    .replace(Regex("[^A-ZА-Я0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
