package sipgsb.sip

import java.security.MessageDigest

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.ISO_8859_1))
        .joinToString("") { "%02x".format(it) }

enum class Direction { SENT, RECEIVED }

enum class Transport { UDP, TCP }

/**
 * A raw SIP delivery as committed to the archive. Identity is derived from
 * raw bytes + direction + connection generation + receive time, so connection
 * reuse or reconnects can never erase message identity.
 */
data class SipMessage(
    val id: String,
    val raw: String,
    val direction: Direction,
    val transport: Transport,
    val connGen: Int,
    val recvMs: Long,
    val isRequest: Boolean,
    val method: String?,
    val statusCode: Int?,
    val reason: String?,
    val branch: String,
    val callId: String,
    val fromTag: String?,
    val toTag: String?,
    val cseqNum: Long,
    val cseqMethod: String,
    val recordRoutes: List<String>,
    val rawSha: String,
) {
    val summary: String
        get() = if (isRequest) "$method cseq=$cseqNum" else "$statusCode $reason ($cseqMethod)"

    companion object {
        fun messageId(raw: String, direction: Direction, connGen: Int, recvMs: Long): String =
            "m-" + sha256("$direction|$connGen|$recvMs|" + raw).take(16)
    }
}

/** A parse failure keeps the raw bytes in place and attaches diagnostics. */
data class ParseDiagnostic(
    val raw: String,
    val direction: Direction,
    val transport: Transport,
    val connGen: Int,
    val recvMs: Long,
    val errors: List<String>,
) {
    val id: String = "bad-" + sha256("$direction|$connGen|$recvMs|" + raw).take(12)
}

sealed interface ParseOutcome {
    data class Ok(val message: SipMessage) : ParseOutcome
    data class Failed(val diagnostic: ParseDiagnostic) : ParseOutcome
}
