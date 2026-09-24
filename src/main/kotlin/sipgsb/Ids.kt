package sipgsb

import java.security.MessageDigest

/** Stable, content-derived identities. Same bytes in -> same id out, across export/import. */
object Ids {
    fun sha1(text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun fingerprint(parts: Iterable<String?>): String =
        sha1(parts.joinToString("") { (it ?: "�") }).take(16)

    /** Message identity: deliberately excludes connection id/generation so that
     *  connection reuse or TCP reconnect can never erase message identity. */
    fun messageId(direction: Direction, recvMs: Long, rawBytes: String): String =
        "m" + fingerprint(listOf(direction.name, recvMs.toString(), rawBytes))

    /** Transaction key per RFC 3261 §17: branch + method (+ Call-ID for uniqueness across calls). */
    fun transactionId(branch: String?, cseqMethod: String?, callId: String?): String =
        "t" + fingerprint(listOf(branch, cseqMethod?.uppercase(), callId))

    fun dialogId(callId: String?, localTag: String?, remoteTag: String?): String =
        "d" + fingerprint(listOf(callId, localTag, remoteTag))

    fun candidateId(kind: String, vararg parts: String?): String =
        "c" + fingerprint(listOf(kind) + parts.toList())

    fun derivedId(kind: String, objectId: String, algoVersion: String, paramsFp: String, valueJson: String): String =
        "v" + fingerprint(listOf(kind, objectId, algoVersion, paramsFp, valueJson))

    fun paramsFingerprint(params: EngineParams): String =
        fingerprint(listOf(
            params.t1Ms.toString(), params.t2Ms.toString(), params.t4Ms.toString(),
            params.timerLMs.toString(), params.timerIMs.toString(), params.seed.toString()))
}
