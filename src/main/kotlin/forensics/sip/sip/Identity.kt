package forensics.sip.sip

import java.security.MessageDigest

object Identity {
    private val sha = MessageDigest.getInstance("SHA-256")
    fun sha256Hex(text: String): String = sha.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun sha256Short(prefix: String, text: String): String = prefix + sha256Hex(text).take(16)
    fun fingerprint(params: Map<String, String>): String =
        params.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
            .let { "fp-" + sha256Hex(it).take(16) }
}
