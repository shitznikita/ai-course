import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

val appJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull

fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
