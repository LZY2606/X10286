package sipreview.model

import java.security.MessageDigest

object Digest {
    fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun sha256Bytes(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(b).joinToString("") { "%02x".format(it) }
    }
    fun short(s: String): String = sha256(s).take(12)
}
