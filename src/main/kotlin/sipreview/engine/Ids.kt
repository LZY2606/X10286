package sipreview.engine

import java.security.MessageDigest

/** Deterministic, export-safe identifiers: identical content always gets the same id. */
object Ids {
    fun sha1(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-1")
        parts.forEach { md.update(it.toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    fun prefixed(prefix: String, vararg parts: String): String =
        "$prefix${sha1(parts.toList()).take(20)}"
}
