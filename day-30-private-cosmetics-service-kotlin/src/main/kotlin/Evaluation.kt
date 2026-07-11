import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class EvalCasesDocument(
    val version: String,
    val updatedAt: String,
    val statusDefinitions: Map<String, String>,
    val cases: List<EvalCase>,
)

@Serializable
data class EvalCase(
    val id: String,
    val type: String,
    val input: EvalInput,
    val expectations: EvalExpectations,
)

@Serializable
data class EvalInput(
    val mode: String,
    val value: String,
    val nameHint: String? = null,
    val market: String? = null,
    val asOf: String? = null,
)

@Serializable
data class EvalExpectations(
    val expectedProductType: String? = null,
    val expectedIngredientIds: List<String>,
    val expectedStatus: String,
)

object OfflineEvaluation {
    fun run(knowledge: IngredientKnowledgeBase, file: Path, maxCards: Int) {
        val document = try {
            AppJson.strict.decodeFromString<EvalCasesDocument>(Files.readString(file))
        } catch (error: Exception) {
            throw KnowledgeException("Cannot read eval cases from $file: ${error.message}", error)
        }
        var passed = 0
        println("DAY 30 OFFLINE EVALUATION")
        document.cases.forEach { case ->
            val actual = evaluate(case, knowledge, maxCards)
            val expected = case.expectations
            val ok = actual.status == expected.expectedStatus &&
                actual.ingredientIds == expected.expectedIngredientIds &&
                actual.productType == expected.expectedProductType
            if (ok) passed += 1
            println(
                "${if (ok) "PASS" else "FAIL"} ${case.id}: " +
                    "status=${actual.status}, type=${actual.productType ?: "-"}, ingredients=${actual.ingredientIds.size}",
            )
        }
        println("RESULT: $passed/${document.cases.size} deterministic cases passed")
        if (passed != document.cases.size) throw KnowledgeException("Offline evaluation failed.")
    }

    private fun evaluate(case: EvalCase, knowledge: IngredientKnowledgeBase, maxCards: Int): EvalActual {
        if (InciParser.runCatchingInstruction(case.input.value)) return EvalActual("rejected", null, emptyList())
        return if (case.input.mode == "name") {
            val product = knowledge.findProductExact(case.input.value)
                ?: return EvalActual("needs_input", null, emptyList())
            val version = knowledge.versionAt(product, case.input.market, case.input.asOf)
                ?: return EvalActual("needs_input", product.category, emptyList())
            val pack = knowledge.retrieve(version.inci, maxCards)
            EvalActual("ok", product.category, pack.recognized.map { it.card.id })
        } else {
            val pack = knowledge.retrieve(case.input.value, maxCards)
            val status = when {
                pack.recognized.isEmpty() -> "needs_input"
                pack.unknown.isNotEmpty() -> "partial"
                else -> "ok"
            }
            EvalActual(status, inferType(case.input.nameHint), pack.recognized.map { it.card.id })
        }
    }

    private fun inferType(hint: String?): String? {
        val value = hint?.lowercase().orEmpty()
        return when {
            "сывор" in value || "serum" in value -> "face_serum"
            "умыван" in value || "cleanser" in value || "wash" in value -> "face_cleanser"
            "тоник" in value || "toner" in value -> "face_toner"
            "spf" in value || "sunscreen" in value || "солнц" in value -> "face_sunscreen"
            "крем" in value || "cream" in value -> "face_moisturizer"
            else -> null
        }
    }

    private data class EvalActual(
        val status: String,
        val productType: String?,
        val ingredientIds: List<String>,
    )
}
