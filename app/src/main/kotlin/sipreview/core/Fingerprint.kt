package sipreview.core

import java.security.MessageDigest

object Fingerprint {
    fun of(vararg parts: String): String =
        MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
