package dev.sipflow.core

import java.security.MessageDigest

object Hashing {
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun shortId(prefix: String, content: String): StableId {
        val h = sha256Hex(content)
        return "$prefix_${h.take(16)}"
    }
}
