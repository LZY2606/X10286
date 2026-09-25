package sipgsb.sip

enum class Direction { C2S, S2C }

/** A parsed SIP message. Raw bytes always live on the InputRecord; this is the derived parse. */
data class SipMessage(
    val isRequest: Boolean,
    val method: String?,          // request method; null for responses
    val uri: String?,             // request URI, raw representation kept
    val statusCode: Int?,         // response code; null for requests
    val statusText: String?,
    val branch: String,           // Via branch (magic cookie included as sent)
    val viaSentBy: String,
    val viaTransport: String,     // UDP / TCP / ... as declared
    val callId: String,
    val fromTag: String?,
    val toTag: String?,
    val cseqNum: Long,
    val cseqMethod: String,
    val recordRoutes: List<String>, // raw header values, original order, NOT normalized
    val contact: String?,
    val headers: Map<String, List<String>>, // lower-cased name -> raw values
)

/** Envelope around one raw message as submitted by the operator (bulk import format). */
data class Envelope(
    val direction: Direction,
    val connEpoch: Int,
    val recvMs: Long,             // virtual clock, unit: ms
    val transport: String,        // udp | tcp
    val rawBytes: String,
)

sealed interface ParseOutcome {
    data class Ok(val message: SipMessage) : ParseOutcome
    data class Err(val error: String) : ParseOutcome
}
