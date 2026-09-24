package app

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val ALGO_VERSION = "sip-recon-1.0.0"
const val CLOCK_BASIS = "virtual-clock-v1"
const val TIME_UNIT = "ms"

val J: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun sha256hex(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun shortId(prefix: String, key: String) = "$prefix-${sha256hex(key).substring(0, 12)}"

/** A domain quantity: value + unit + coordinate basis. Raw representation is always preserved at import. */
data class Quantity(val valueMs: Long?, val raw: String, val diagnostic: String?) {
    fun toJson() = buildJsonObject {
        put("raw", raw)
        if (valueMs != null) {
            put("value", valueMs)
            put("unit", TIME_UNIT)
            put("basis", CLOCK_BASIS)
        }
        if (diagnostic != null) put("diagnostic", diagnostic)
    }
}

/** Parse a time quantity like "1500", "1500ms", "1.5s", "2m". Unconvertible values stay raw with a diagnostic. */
fun parseMs(raw: String): Quantity {
    val t = raw.trim().lowercase()
    val m = Regex("^([0-9]+(?:\\.[0-9]+)?)(ms|s|m)?$").matchEntire(t)
        ?: return Quantity(null, raw, "unparseable time quantity '$raw'; kept in raw form, not normalized")
    val num = m.groupValues[1].toDouble()
    return when (val unit = m.groupValues[2].ifEmpty { "ms" }) {
        "ms" -> Quantity(num.toLong(), raw, null)
        "s" -> Quantity((num * 1000).toLong(), raw, null)
        "m" -> Quantity((num * 60000).toLong(), raw, null)
        else -> Quantity(null, raw, "unsupported unit '$unit' in '$raw'; kept in raw form")
    }
}

fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        null -> {}
        is String -> put(k, v)
        is Number -> put(k, v)
        is Boolean -> put(k, v)
        is JsonObject -> put(k, v)
        is kotlinx.serialization.json.JsonElement -> put(k, v)
        else -> put(k, v.toString())
    }
}
