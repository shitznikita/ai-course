import java.text.DecimalFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

val appJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

val moneyFormat = DecimalFormat("0.000000")

fun JsonElement.asInt(): Int? = jsonPrimitive.intOrNull

fun JsonElement.asDouble(): Double? = jsonPrimitive.doubleOrNull
